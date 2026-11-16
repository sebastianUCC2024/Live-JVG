package com.live_jgv.notification.infrastructure.amqp;

import static org.junit.jupiter.api.Assertions.*;
import com.live_jgv.notification.domain.IdentityConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.transaction.*;

class ProcessingFailurePolicyTest {
    final ProcessingFailurePolicy policy=new ProcessingFailurePolicy();
    @Test void permanentValidationIntegritySyntaxAndPermissionErrorsAreNotRetried() {
        for (var failure:new RuntimeException[]{new IdentityConflictException(),new IllegalArgumentException(),
                new DataIntegrityViolationException("constraint"),new PermissionDeniedDataAccessException("permission",null),
                new InvalidDataAccessResourceUsageException("bad SQL")}) {
            assertEquals(ProcessingFailurePolicy.Category.PERMANENT,policy.classify(failure));
            assertFalse(policy.retry(failure,1,3));
        }
    }
    @Test void temporaryConnectionsLocksAndUncertainTransactionsHaveBoundedRetries() {
        for (var failure:new RuntimeException[]{new DataAccessResourceFailureException("offline"),
                new CannotAcquireLockException("lock"),new CannotCreateTransactionException("connect"),new TransactionSystemException("commit uncertain")}) {
            assertEquals(ProcessingFailurePolicy.Category.TRANSIENT,policy.classify(failure));
            assertTrue(policy.retry(failure,1,3)); assertFalse(policy.retry(failure,3,3));
        }
    }
    @Test void unknownRuntimeErrorsRemainUnknownAndReceiveBoundedRetries() {
        var failure=new IllegalStateException("unknown");
        assertEquals(ProcessingFailurePolicy.Category.UNKNOWN,policy.classify(failure));
        assertTrue(policy.retry(failure,1,3)); assertFalse(policy.retry(failure,3,3));
    }
    @Test void wrappedPermanentCauseOverridesTransientWrapperAndCyclesTerminate() {
        assertEquals(ProcessingFailurePolicy.Category.PERMANENT,policy.classify(new TransactionSystemException("wrapper",new DataIntegrityViolationException("constraint"))));
        var first=new RuntimeException(); var second=new RuntimeException(first); first.initCause(second);
        assertEquals(ProcessingFailurePolicy.Category.UNKNOWN,policy.classify(first));
    }
}
