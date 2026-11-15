package com.live_jgv.matchstate.domain.model;

import java.util.Objects;

/** Transport-neutral original bytes and occurrence coordinates, not a sporting event. */
public record InputRecord(String source, String topic, int partition, long offset,
                          long timestamp, byte[] key, byte[] value, String headers) {
    public InputRecord {
        Objects.requireNonNull(source); Objects.requireNonNull(topic); Objects.requireNonNull(headers);
        key = key == null ? null : key.clone(); value = value == null ? null : value.clone();
    }
    @Override public byte[] key() { return key == null ? null : key.clone(); }
    @Override public byte[] value() { return value == null ? null : value.clone(); }
}
