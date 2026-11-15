package com.live_jgv.matchstate.infrastructure.jdbc;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Simulated PostgreSQL session locks; deliberately no database or broker. */
class PostgresPublicationLockTest {
    static class Server {
        final Map<Long, Connection> owners = new ConcurrentHashMap<>();
        final List<Connection> connections = new CopyOnWriteArrayList<>();
        final DataSource source = mock(DataSource.class);
        Server() throws Exception {
            when(source.getConnection()).thenAnswer(call -> session());
        }
        Connection session() throws Exception {
            var connection = mock(Connection.class);
            connections.add(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var statement = mock(PreparedStatement.class);
                var key = new AtomicLong();
                doAnswer(c -> { key.set(c.getArgument(1)); return null; }).when(statement).setLong(eq(1), anyLong());
                when(statement.executeQuery()).thenAnswer(c -> {
                    boolean result = sql.contains("pg_try_advisory_lock")
                            ? owners.putIfAbsent(key.get(), connection) == null
                            : owners.remove(key.get(), connection);
                    var rows = mock(ResultSet.class);
                    when(rows.next()).thenReturn(true, false);
                    when(rows.getBoolean(1)).thenReturn(result);
                    return rows;
                });
                return statement;
            });
            doAnswer(c -> { owners.values().removeIf(owner -> owner == connection); return null; })
                    .when(connection).abort(any());
            return connection;
        }
    }

    @Test void twoInstancesCannotPublishSameMatchAndOtherMatchesDoNotWait() throws Exception {
        var server = new Server();
        var first = new PostgresPublicationLock(server.source);
        var second = new PostgresPublicationLock(server.source);
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> first.execute("m1", jdbc -> {
                assertEquals(1, server.owners.size());
                publishing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                sent.incrementAndGet();
            }));
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> {
                assertThrows(IllegalStateException.class, () -> second.execute("m1", jdbc -> fail("Concurrent send")));
                second.execute("m2", jdbc -> sent.incrementAndGet());
            });
            b.get(5, TimeUnit.SECONDS);
            assertEquals(1, sent.get());
            release.countDown(); a.get(5, TimeUnit.SECONDS);
            assertEquals(2, sent.get());
            second.execute("m1", jdbc -> assertTrue(server.owners.containsKey(PostgresPublicationLock.key("m1"))));
            assertTrue(server.owners.isEmpty());
            for (var connection : server.connections) {
                verify(connection).close();
                verify(connection, never()).setAutoCommit(false);
                verify(connection, never()).commit();
            }
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void publisherExceptionReleasesLockAndClosesSessionBeforeRetry() throws Exception {
        var server = new Server(); var lane = new PostgresPublicationLock(server.source);
        var brokerFailure = new IllegalArgumentException("uncertain broker confirmation");
        assertSame(brokerFailure, assertThrows(IllegalArgumentException.class,
                () -> lane.execute("m1", jdbc -> { throw brokerFailure; })));
        assertTrue(server.owners.isEmpty());
        verify(server.connections.get(0)).close();
        lane.execute("m1", jdbc -> { });
        assertTrue(server.owners.isEmpty());
    }

    @Test void unknownAcquisitionAndFailedUnlockAbortPhysicalSession() throws Exception {
        for (boolean acquisitionFails : List.of(true, false)) {
            var source = mock(DataSource.class); var connection = mock(Connection.class);
            when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
            var lock = mock(PreparedStatement.class); var unlock = mock(PreparedStatement.class);
            when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lock);
            when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true); when(rows.getBoolean(1)).thenReturn(true);
            if (acquisitionFails) when(lock.executeQuery()).thenThrow(new SQLException("reply lost"));
            else { when(lock.executeQuery()).thenReturn(rows); when(unlock.executeQuery()).thenThrow(new SQLException("session lost")); }
            assertThrows(IllegalStateException.class, () -> new PostgresPublicationLock(source).execute("m", jdbc -> { }));
            verify(connection).abort(any()); verify(connection).close();
            verify(lock).close();
        }
    }

    @Test void ambientTransactionAndNonAutocommitAreRejectedBeforePublication() throws Exception {
        var server = new Server(); var lane = new PostgresPublicationLock(server.source);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> lane.execute("m", jdbc -> fail())); }
        finally { TransactionSynchronizationManager.clear(); }
        verify(server.source, never()).getConnection();
        var connection = mock(Connection.class); when(server.source.getConnection()).thenReturn(connection);
        assertThrows(IllegalStateException.class, () -> lane.execute("m", jdbc -> fail()));
        verify(connection).close(); verify(connection, never()).prepareStatement(anyString());
    }
}
