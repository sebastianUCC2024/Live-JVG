package com.live_jgv.matchstate.entrypoint.kafka;

public final class InvalidKafkaMessageException extends RuntimeException {
    public InvalidKafkaMessageException(Throwable cause) {
        super("Kafka message does not match the provisional match-event format", cause);
    }
}
