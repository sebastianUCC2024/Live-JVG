package com.live_jgv.notification.infrastructure.amqp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmTimeoutTest {
    @ParameterizedTest @ValueSource(longs={-1,0,1,999999})
    void rejectsDurationsBelowOneMillisecond(long nanos) {
        assertThrows(IllegalArgumentException.class,()->new ConfirmedDlqRecovery(mock(RabbitTemplate.class),"notification.dlx",Duration.ofNanos(nanos)));
    }
    @Test void roundsFractionalMillisecondsUpInsteadOfShorteningTimeout() {
        assertEquals(1,ConfirmedDlqRecovery.timeoutMillis(Duration.ofMillis(1)));
        assertEquals(2,ConfirmedDlqRecovery.timeoutMillis(Duration.ofNanos(1000001)));
        assertEquals(5000,ConfirmedDlqRecovery.timeoutMillis(Duration.ofSeconds(5)));
    }
    @Test void rejectsNullAndOverflowAndAcceptsLargestExactMillisecondDuration() {
        assertThrows(IllegalArgumentException.class,()->ConfirmedDlqRecovery.timeoutMillis(null));
        assertEquals(Long.MAX_VALUE,ConfirmedDlqRecovery.timeoutMillis(Duration.ofMillis(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,()->ConfirmedDlqRecovery.timeoutMillis(Duration.ofMillis(Long.MAX_VALUE).plusNanos(1)));
        assertThrows(IllegalArgumentException.class,()->ConfirmedDlqRecovery.timeoutMillis(Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
