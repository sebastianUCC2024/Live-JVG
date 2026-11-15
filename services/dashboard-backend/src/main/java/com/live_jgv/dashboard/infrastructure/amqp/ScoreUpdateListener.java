package com.live_jgv.dashboard.infrastructure.amqp;

import com.live_jgv.dashboard.domain.port.in.ProcessScoreUpdate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

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
