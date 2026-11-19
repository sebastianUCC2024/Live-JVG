package com.live_jgv.notification.integration;

import java.util.List;

/** Expected prepared migration inventory; no connections or migration execution. */
final class NotificationMigrationInventory {
    static final List<String> FILES=List.of("V1__notification_inbox_and_alerts.sql","V2__live_alert_identity.sql");
    static final List<String> VERSIONS=List.of("1","2");
    static final List<String> TABLES=List.of("alerts","cursor_state","flyway_schema_history","inbox","live_alert_inbox");
    private NotificationMigrationInventory() { }
}
