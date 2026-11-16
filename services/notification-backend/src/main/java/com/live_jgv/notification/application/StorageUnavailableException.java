package com.live_jgv.notification.application;
public final class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException() { super("Notification storage is disabled"); }
}
