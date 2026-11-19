package com.live_jgv.betting.domain;

import java.util.Objects;

/** Conserva los bytes originales de Kafka, incluidos tombstones y UTF-8 inválido. */
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
