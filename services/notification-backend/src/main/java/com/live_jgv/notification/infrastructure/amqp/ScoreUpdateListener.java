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

/** Recibe notification.messaging.queue; guarda alertas y usa ACK manual tras el commit o transferencia confirmada a DLQ. */
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
    private com.live_jgv.notification.infrastructure.json.LiveAlertCodec alertCodec;
    private java.util.Set<String> allowedMatches;
    public ScoreUpdateListener withLiveAlerts(java.util.Set<String> matches) {
        if(matches.isEmpty() || matches.stream().anyMatch(id->!id.matches("[A-Za-z0-9_.-]+"))) throw new IllegalArgumentException("Approved scope required");
        this.alertCodec=new com.live_jgv.notification.infrastructure.json.LiveAlertCodec(); this.allowedMatches=java.util.Set.copyOf(matches); return this;
    }
    private boolean allowedMatch(String id) {
        return allowedMatches.contains(id)
                || (id.matches("demo-col-v4-00[1-6][.][a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                && allowedMatches.contains(id.substring(0, id.lastIndexOf('.'))));
    }
    private final ProcessingFailurePolicy failurePolicy=new ProcessingFailurePolicy();
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
            var alert=alertCodec==null?null:alertCodec.decode(message.getBody());
            var update = alert==null?codec.decode(message.getBody()):alert.snapshot();
            var properties = message.getMessageProperties();
            if(alert!=null && (!allowedMatch(alert.matchId()) || !"live_alerts".equals(properties.getReceivedExchange()) || !alert.alertId().toString().equals(properties.getMessageId()))) throw new InvalidUpdateException("LiveAlert AMQP identity or scope mismatch");
            if (alert==null && ((properties.getMessageId() != null && !properties.getMessageId().equals(update.eventId()))
                    || (properties.getReceivedRoutingKey() != null && !properties.getReceivedRoutingKey().equals("score." + update.matchId()))))
                throw new InvalidUpdateException("AMQP identity or routing key mismatch");
            for (int attempt=1; ; attempt++) {
                try {
                    var result=alert==null?processor.process(update):processor.process(alert);
                    LOG.info("Notification input processed; result={}, matchId={}, eventId={}",result,update.matchId(),update.eventId());
                    break;
                }
                catch (RuntimeException processingFailure) {
                    if (!failurePolicy.retry(processingFailure,attempt,attempts)) throw processingFailure;
                    LOG.warn("Notification processing retry; category={}, attempt={}",failurePolicy.classify(processingFailure),attempt);
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
        // Un fallo de ACK no repite el trabajo dentro de la misma entrega.
        // El inbox persistente gestiona las reentregas del broker.
        channel.basicAck(tag, false);
    }
    private void retain(Message message, Channel channel, long tag) throws IOException {
        LOG.error("Notification consumer stopping; original not acknowledged; redelivered={}", message.getMessageProperties().isRedelivered());
        try { channel.basicNack(tag, false, true); }
        finally { stop.run(); }
    }
}
