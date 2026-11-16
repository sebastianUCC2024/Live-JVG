package com.live_jgv.betting.application;

/** Return only after confirmed mandatory routing; callers must retain uncertain publications. */
public interface SuspensionPublisher {
    void publish(String messageId,String payload);
}
