package com.live_jgv.dashboard.infrastructure.amqp;

import com.live_jgv.dashboard.application.port.in.ProcessScoreUpdate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Decodes RabbitMQ score JSON and invokes ProcessScoreUpdate for the memory projection.
 * DashboardRabbitConfiguration selects this adapter only for the memory profile and uses
 * AUTO acknowledgment after normal return, one consumer and prefetch one. Invalid input
 * is non-retryable; exhausted failures are rejected to the configured dead-letter topology.
 * The volatile projection does not provide restart-safe duplicate detection or history.
 */
public final class ScoreUpdateListener {
    private final ScoreUpdateMessageDecoder decoder;
    private final ProcessScoreUpdate processor;

    public ScoreUpdateListener(ScoreUpdateMessageDecoder decoder, ProcessScoreUpdate processor) {
        this.decoder = decoder;
        this.processor = processor;
    }

    @RabbitListener(queues = "${dashboard.rabbit.queue}", containerFactory = "dashboardListenerFactory")
    public void onMessage(Message message) {
        processor.process(decoder.decode(message.getBody()));
    }
}
