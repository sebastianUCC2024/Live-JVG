package com.live_jgv.notification.infrastructure.amqp;

import com.live_jgv.notification.application.ProcessScoreUpdateService;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScoreUpdateListener {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private static final Logger LOG = LoggerFactory.getLogger(ScoreUpdateListener.class);
    private final ProcessScoreUpdateService processor;
    private final ScoreUpdateCodec codec;
    private final ConfirmedDlqRecovery recovery;
    private final Runnable stop;
    private final Sleeper sleeper;
    private final int attempts;
    private final long interval;
    public ScoreUpdateListener(ProcessScoreUpdateService processor, ScoreUpdateCodec codec,
            ConfirmedDlqRecovery recovery, Runnable stop, Sleeper sleeper, int attempts, long interval) {
        if (attempts < 1 || attempts > 10 || interval < 1 || interval > 10000) throw new IllegalArgumentException("Invalid bounded retry settings");
        this.processor=processor; this.codec=codec; this.recovery=recovery; this.stop=stop;
        this.sleeper=sleeper; this.attempts=attempts; this.interval=interval;
    }
    @RabbitListener(id="notificationScores", queues="${notification.messaging.queue}", containerFactory="notificationListenerFactory")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            var update = codec.decode(message.getBody());
            var properties = message.getMessageProperties();
            if ((properties.getMessageId() != null && !properties.getMessageId().equals(update.eventId()))
                    || (properties.getReceivedRoutingKey() != null && !properties.getReceivedRoutingKey().equals("score." + update.matchId())))
                throw new InvalidUpdateException("AMQP identity or routing key mismatch");
            for (int attempt=1; ; attempt++) {
                try {
                    var result=processor.process(update);
                    LOG.info("Notification input processed; result={}, matchId={}, eventId={}",result,update.matchId(),update.eventId());
                    break;
                }
                catch (IllegalArgumentException permanent) { throw permanent; }
                catch (RuntimeException transientFailure) {
                    if (attempt >= attempts) throw transientFailure;
                    sleeper.sleep(Math.min(30000, interval * (1L << (attempt - 1))));
                }
            }
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt(); retain(message, channel, tag); return;
        } catch (RuntimeException failure) {
            try { recovery.transfer(message, failure); }
            catch (ConfirmedDlqRecovery.CustodyUnavailableException unavailable) {
                retain(message, channel, tag); return;
            }
            LOG.warn("Notification input transferred to DLQ; failureType={}", failure.getClass().getSimpleName());
        }
        // Outside processing retries: an ACK I/O failure must not repeat business work
        // within this delivery. Broker redelivery is handled by the durable inbox.
        channel.basicAck(tag, false);
    }
    private void retain(Message message, Channel channel, long tag) throws IOException {
        LOG.error("Notification consumer stopping; original not acknowledged; redelivered={}", message.getMessageProperties().isRedelivered());
        try { channel.basicNack(tag, false, true); }
        finally { stop.run(); }
    }
}
