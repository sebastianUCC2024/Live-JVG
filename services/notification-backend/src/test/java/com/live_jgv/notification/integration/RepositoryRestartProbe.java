package com.live_jgv.notification.integration;

import java.util.Set;

/** A fresh JVM reads persisted alerts; it never migrates or writes. */
public final class RepositoryRestartProbe {
    public static void main(String[] args) {
        try {
            var authorization=IntegrationGate.authorize(Set.of("database-read","restart-probe"));
            var settings=authorization.settings();
            if (args.length!=1 || !args[0].startsWith(settings.match(""))) throw IntegrationSettings.refused("Match outside approved run");
            var fixture=new PostgresFixture(authorization,false);
            var page=fixture.store.query(args[0],null,100);
            System.out.println("NOTIFICATION_RESTART_RESULT="+IntegrationGate.JSON.writeValueAsString(page.items().stream().map(alert->alert.alertId().toString()).toList()));
        } catch (Exception refused) {
            System.err.println("Restart probe refused or failed; sensitive details withheld"); System.exit(2);
        }
    }
}
