package com.live_jgv.archiver.domain;

import java.util.Objects;

/** Immutable original transport evidence, including Kafka tombstones and invalid UTF-8. */
public record InputRecord(String sourceId, String topic, int partition, long offset,
                          long brokerTimestamp, byte[] key, byte[] value, String headers) {
    public InputRecord {
        if (sourceId == null || sourceId.isBlank() || topic == null || topic.isBlank()
                || partition < 0 || offset < 0) throw new IllegalArgumentException("Invalid source position");
        Objects.requireNonNull(headers);
        key = key == null ? null : key.clone(); value = value == null ? null : value.clone();
    }
    @Override public byte[] key() { return key == null ? null : key.clone(); }
    @Override public byte[] value() { return value == null ? null : value.clone(); }
}
