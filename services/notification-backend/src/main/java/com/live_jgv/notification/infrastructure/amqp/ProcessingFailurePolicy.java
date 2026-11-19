package com.live_jgv.notification.infrastructure.amqp;

import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** Clasifica fallos y limita reintentos, sin descartar errores desconocidos. */
public final class ProcessingFailurePolicy {
    public enum Category { TRANSIENT, PERMANENT, UNKNOWN }
    public Category classify(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        boolean transientCause=false;
        for (Throwable cause=failure; cause!=null && seen.size()<32 && seen.add(cause); cause=cause.getCause()) {
            if (cause instanceof IllegalArgumentException
                    || (cause instanceof NonTransientDataAccessException && !(cause instanceof DataAccessResourceFailureException))
                    || cause instanceof java.sql.SQLIntegrityConstraintViolationException
                    || cause instanceof java.sql.SQLSyntaxErrorException
                    || cause instanceof java.sql.SQLInvalidAuthorizationSpecException) return Category.PERMANENT;
            if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException || cause instanceof CannotCreateTransactionException
                    || cause instanceof TransactionSystemException || cause instanceof java.sql.SQLTransientException
                    || cause instanceof java.sql.SQLRecoverableException) transientCause=true;
        }
        return transientCause ? Category.TRANSIENT : Category.UNKNOWN;
    }
    public boolean retry(Throwable failure, int attempt, int maxAttempts) {
        return attempt<maxAttempts && classify(failure)!=Category.PERMANENT;
    }
}
