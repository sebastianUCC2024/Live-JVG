package com.live_jgv.matchstate.infrastructure.jdbc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bloqueo de sesión: no mantiene una transacción abierta mientras espera a RabbitMQ. */
final class PostgresPublicationLock {
    private final DataSource source;

    PostgresPublicationLock(DataSource source) { this.source = source; }

    void execute(String matchId, Consumer<JdbcTemplate> publication) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox publication must run outside a transaction");
        }
        long key = key(matchId);
        try (var connection = source.getConnection()) {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Publication requires autocommit");
            boolean locked = false;
            Throwable primary = null;
            try {
                locked = lockFunction(connection, "pg_try_advisory_lock", key);
                if (!locked) throw new IllegalStateException("Outbox publication lane busy; retry required");
                // Las lecturas y confirmaciones usan la misma sesión bloqueada.
                var session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                session.setQueryTimeout(5);
                publication.accept(session);
            } catch (RuntimeException | Error | SQLException failure) {
                primary = failure;
                throw failure;
            } finally {
                if (locked) {
                    try {
                        if (!lockFunction(connection, "pg_advisory_unlock", key)) {
                            throw new SQLException("Publication lock ownership lost");
                        }
                    } catch (SQLException | RuntimeException failure) {
                        // No devuelve al pool una sesión que podría conservar el bloqueo.
                        abort(connection, failure);
                        if (primary != null) primary.addSuppressed(failure);
                        else throw new IllegalStateException("Cannot release publication session", failure);
                    }
                }
                // El servidor pudo adquirir el bloqueo aunque se perdiera su respuesta.
                else if (primary instanceof SQLException) abort(connection, primary);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Publication session failed; retry required", failure);
        }
    }

    private void abort(Connection connection, Throwable failure) {
        try { connection.abort(Runnable::run); }
        catch (Exception abortFailure) {
            failure.addSuppressed(abortFailure);
            try { connection.unwrap(Connection.class).close(); }
            catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
        }
    }

    private boolean lockFunction(Connection connection, String function, long key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setQueryTimeout(5);
            statement.setLong(1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing advisory lock result");
                return rows.getBoolean(1);
            }
        }
    }

    static long key(String matchId) {
        if (matchId == null || matchId.isBlank()) throw new IllegalArgumentException("Match required");
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest(("live-jvg:score-outbox:v1:" + matchId).getBytes(StandardCharsets.UTF_8))).getLong();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
