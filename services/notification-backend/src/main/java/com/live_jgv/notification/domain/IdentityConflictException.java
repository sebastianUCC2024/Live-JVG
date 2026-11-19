package com.live_jgv.notification.domain;
public final class IdentityConflictException extends InvalidUpdateException {
    public IdentityConflictException() { super("Source event identity reused with different content"); }
}
