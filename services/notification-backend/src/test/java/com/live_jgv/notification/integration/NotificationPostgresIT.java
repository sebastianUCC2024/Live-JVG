package com.live_jgv.notification.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.application.NotificationStore;
import com.live_jgv.notification.infrastructure.web.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Tag("external-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NotificationPostgresIT {
    @org.junit.jupiter.api.extension.RegisterExtension static final IntegrationObserver observer=new IntegrationObserver();
    static PostgresFixture db; static IntegrationEvidence evidence;
    static String match(String scenario) { return db.settings.match(scenario+"_"+UUID.randomUUID()); }
    @BeforeAll static void authorizedSetup() throws Exception {
        var actions=new HashSet<>(PostgresFixture.ACTIONS); actions.add("restart-probe");
        var authorization=IntegrationGate.authorize(actions);
        evidence=new IntegrationEvidence(authorization.settings()); observer.use(evidence); db=new PostgresFixture(authorization,true);
    }
    @Test @Order(1) void flywayV1CreatesOnlyExpectedBusinessTablesAndIsRepeatable() throws Exception {
        var tables=db.jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='notification' ORDER BY tablename",String.class);
        assertEquals(List.of("alerts","cursor_state","flyway_schema_history","inbox"),tables);
        new PostgresFixture(db.authorization,true); // migrate+validate again; never clean.
        assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM notification.flyway_schema_history WHERE success AND version='1'",Integer.class));
        evidence.record("migration",Map.of("result","V1_VALIDATED"));
    }
    @Test void uniquenessAndDuplicateIdentityAreDurable() throws Exception {
        String match=match("unique"); var update=db.update(match,"event-unique",ScoreUpdate.EventType.GOAL);
        assertEquals(NotificationStore.Result.CREATED,db.service().process(update));
        assertEquals(NotificationStore.Result.DUPLICATE,db.service().process(update));
        assertThrows(DataIntegrityViolationException.class,()->db.jdbc.update("INSERT INTO notification.inbox(source,match_id,event_id,payload,outcome) VALUES ('match-state-service',?,?,?::jsonb,'ALERT')",match,update.eventId(),db.codec.encode(update)));
        assertEquals(1,db.inbox(match)); assertEquals(1,db.alerts(match));
        var conflicting=new ScoreUpdate(match,update.eventId(),update.eventType(),2,0,update.varStatus(),update.updatedAt(),null);
        assertThrows(IdentityConflictException.class,()->db.service().process(conflicting));
        evidence.record("unique",Map.of("event_id",update.eventId(),"alert_id",db.store.query(match,null,50).items().get(0).alertId().toString(),"rows",db.alerts(match)));
    }
    @Test void failureAfterInboxInsertRollsBackInboxAlertAndCounter() throws Exception {
        String match=match("rollback"); var update=db.update(match,"rollback-event",ScoreUpdate.EventType.GOAL);
        long before=db.jdbc.queryForObject("SELECT last_sequence FROM notification.cursor_state",Long.class);
        var invalidAlert=new LiveAlert(UUID.randomUUID(),update,Instant.now(),new LiveAlert.Content("es","Gol","x".repeat(501)));
        assertThrows(DataIntegrityViolationException.class,()->db.store.accept(update,Optional.of(invalidAlert)));
        assertEquals(0,db.inbox(match)); assertEquals(0,db.alerts(match));
        assertEquals(before,db.jdbc.queryForObject("SELECT last_sequence FROM notification.cursor_state",Long.class));
        evidence.record("rollback",Map.of("event_id",update.eventId(),"rows",db.inbox(match),"result","ROLLED_BACK"));
    }
    @Test void concurrentCopiesCommitExactlyOneAlert() throws Exception {
        String match=match("concurrent"); var update=db.update(match,"same-event",ScoreUpdate.EventType.GOAL);
        var workers=Executors.newFixedThreadPool(8); long start=System.nanoTime();
        try {
            var futures=new ArrayList<Future<NotificationStore.Result>>();
            for (int i=0;i<16;i++) futures.add(workers.submit(()->db.service().process(update)));
            int created=0;
            for (var future:futures) if (future.get(20,TimeUnit.SECONDS)==NotificationStore.Result.CREATED) created++;
            assertEquals(1,created); assertEquals(1,db.inbox(match)); assertEquals(1,db.alerts(match));
            evidence.record("concurrency",Map.of("event_id",update.eventId(),"rows",db.alerts(match),"elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)));
        } finally { workers.shutdownNow(); }
    }
    @Test void newJvmRecoversSamePersistedAlertIdentity() throws Exception {
        String match=match("restart"); db.service().process(db.update(match,"persisted-event",ScoreUpdate.EventType.GOAL));
        String id=db.store.query(match,null,50).items().get(0).alertId().toString();
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var child=new ProcessBuilder(javaExecutable,"-Dnotification.it.profile.enabled=true","-Dbasedir="+IntegrationGate.module(),
                "-Dlogback.configurationFile="+IntegrationGate.module().resolve("src/test/resources/logback-integration.xml"),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),RepositoryRestartProbe.class.getName(),match)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var stopOwnedChild=new Thread(()->{if(child.isAlive()) child.destroyForcibly();},"stop-owned-notification-probe");
        Runtime.getRuntime().addShutdownHook(stopOwnedChild);
        try {
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"Owned restart probe timed out");
            assertEquals(0,child.exitValue());
            String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String result=output.lines().filter(line->line.startsWith("NOTIFICATION_RESTART_RESULT=")).reduce((first,last)->last).orElseThrow();
            var ids=IntegrationGate.JSON.readTree(result.substring("NOTIFICATION_RESTART_RESULT=".length()));
            assertEquals(id,ids.get(0).asText()); evidence.record("restart",Map.of("alert_id",id,"result","RECOVERED_IN_NEW_JVM"));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            try { Runtime.getRuntime().removeShutdownHook(stopOwnedChild); } catch(IllegalStateException shutdown) { }
        }
    }
    @Test void cursorPaginationAndRestAdapterPreserveOrder() throws Exception {
        String match=match("pages"); var first=db.store.query(match,null,2);
        for(int i=0;i<5;i++) db.service().process(db.update(match,"page-"+i,ScoreUpdate.EventType.GOAL));
        var ids=new ArrayList<String>(); String cursor=first.nextCursor(); boolean more;
        do {
            var page=db.store.query(match,cursor,2); ids.addAll(page.items().stream().map(alert->alert.source().eventId()).toList());
            cursor=page.nextCursor(); more=page.hasMore();
        } while(more);
        assertEquals(List.of("page-0","page-1","page-2","page-3","page-4"),ids);
        var mvc=MockMvcBuilders.standaloneSetup(new AlertController(db.store)).setControllerAdvice(new ApiExceptionHandler()).build();
        var response=mvc.perform(get("/api/alerts").param("match_id",match).param("limit","2")).andReturn().getResponse();
        assertEquals(200,response.getStatus()); var json=IntegrationGate.JSON.readTree(response.getContentAsString());
        assertEquals("page-3",json.path("items").get(0).path("source_event_id").asText());
        evidence.record("rest-pages",Map.of("rest_status",response.getStatus(),"rest_items",ids,"has_more",json.path("has_more").asBoolean()));
    }
    @Test void pollingCannotSkipAnUncommittedEarlierWriter() throws Exception {
        String match=match("cursor-commit"); String cursor=db.store.query(match,null,50).nextCursor();
        var worker=Executors.newSingleThreadExecutor();
        try (var lock=db.source.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement=lock.createStatement()) { statement.executeQuery("SELECT singleton FROM notification.cursor_state FOR UPDATE").close(); }
            var future=worker.submit(()->db.service().process(db.update(match,"blocked-writer",ScoreUpdate.EventType.GOAL)));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=db.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE 'UPDATE notification.cursor_state%')",Boolean.class);
                    if(waiting) break; Thread.sleep(25);
                }
                assertTrue(waiting,"Writer must demonstrably wait for the owned cursor row lock");
                assertTrue(db.store.query(match,cursor,50).items().isEmpty());
                lock.commit(); assertEquals(NotificationStore.Result.CREATED,future.get(10,TimeUnit.SECONDS));
                assertEquals("blocked-writer",db.store.query(match,cursor,50).items().get(0).source().eventId());
                evidence.record("cursor-commit",Map.of("result","NO_COMMIT_GAP","event_id","blocked-writer"));
            } finally { lock.rollback(); }
        } finally { worker.shutdownNow(); }
    }
}
