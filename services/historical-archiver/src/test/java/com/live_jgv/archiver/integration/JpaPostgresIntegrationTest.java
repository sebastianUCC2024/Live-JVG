package com.live_jgv.archiver.integration;

import com.live_jgv.archiver.application.*;
import com.live_jgv.archiver.domain.*;
import com.live_jgv.archiver.infrastructure.kafka.ArchiveKafkaListener;
import com.live_jgv.archiver.infrastructure.*;
import com.live_jgv.archiver.infrastructure.jpa.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, NEW PostgreSQL 17 database only. Never uses the E2E laboratory or brokers. */
@EnabledIfEnvironmentVariable(named="ARCHIVER_JPA_POSTGRES_TESTS",matches="true")
class JpaPostgresIntegrationTest {
    static DriverManagerDataSource owner, runtime;
    static JdbcTemplate admin;
    static List<Map<String,Object>> originalSchema;
    AnnotationConfigApplicationContext context;
    JpaArchiveStore store;
    JdbcTemplate jdbc;
    @BeforeAll static void migrateNewApprovedDatabase() {
        owner=IsolatedPostgres.approvedFreshDatabase();admin=new JdbcTemplate(owner);
        String app=System.getenv("ARCHIVER_JPA_IT_APP_USERNAME");
        if(app==null||!app.matches("dev3_it_[a-z0-9_]+_app"))throw new IllegalArgumentException("Dedicated app role required");
        runtime=IsolatedPostgres.configuredDataSource(System.getenv("DEV3_IT_MIGRATION_APPROVAL"),System.getenv("DEV3_IT_DB_URL"),app,System.getenv("ARCHIVER_JPA_IT_APP_PASSWORD"));
        Flyway.configure().dataSource(owner).schemas("archiver").defaultSchema("archiver").cleanDisabled(true).load().migrate();
        // Exactly the existing passive SELECT + academic Archiver DML baseline, no JPA-only privileges.
        admin.execute("GRANT USAGE ON SCHEMA archiver TO "+app);
        admin.execute("GRANT SELECT,INSERT ON archiver.archived_event,archiver.input_receipt TO "+app);
        admin.execute("GRANT UPDATE ON archiver.input_receipt TO "+app);
        admin.execute("GRANT USAGE ON SEQUENCE archiver.archived_event_seq_seq TO "+app);
        originalSchema=fingerprint();
    }
    @BeforeEach void open() {
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->runtime);
        context.register(JpaConfiguration.class);context.refresh();
        store=new JpaArchiveStore(context.getBean(ArchivedEventRepository.class),context.getBean(InputReceiptRepository.class),context.getBean(PlatformTransactionManager.class));
        jdbc=new JdbcTemplate(runtime);
    }
    @AfterEach void close() { if(context!=null)context.close(); }
    @AfterAll static void verifyNoDdlChanges() {
        assertEquals(originalSchema,fingerprint(),"Hibernate must not alter tables, constraints, indexes or ownership");
        assertEquals(List.of("1","2"),admin.queryForList("SELECT version FROM archiver.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",String.class));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM archiver.flyway_schema_history WHERE NOT success",Integer.class));
        Flyway.configure().dataSource(owner).schemas("archiver").defaultSchema("archiver").cleanDisabled(true).load().validate();
    }
    @Test void schemaValidationUsesRealHibernateAndExistingTables() {
        assertTrue(context.getBean(EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class).isOpen());
        assertEquals(1,context.getBeansOfType(PlatformTransactionManager.class).size());
        assertInstanceOf(JpaTransactionManager.class,context.getBean(PlatformTransactionManager.class));
        assertEquals(originalSchema,fingerprint());
    }
    @Test void goalAndAnnulmentPreserveReferenceCanonicalBytesAndAudit() {
        String match=match();var goal=event(match,"goal",SportEvent.EventType.GOAL,null);
        var annul=event(match,"annul",SportEvent.EventType.GOAL_ANNULLED,"goal");
        assertEquals(ArchiveOutcome.STORED,store.save(record(goal,1),decoded(goal)));
        assertEquals(ArchiveOutcome.STORED,store.save(record(annul,2),decoded(annul)));
        var history=store.find(match,0,10);assertEquals(2,history.totalElements());
        assertEquals("GOAL",history.content().get(0).eventType());assertEquals("goal",history.content().get(1).referenceEventId());
        assertEquals("GOAL_ANNULLED",history.content().get(1).eventType());
        assertArrayEquals(record(goal,1).value(),jdbc.queryForObject("SELECT original_payload FROM archiver.archived_event WHERE match_id=? AND event_id='goal'",byte[].class,match));
        assertEquals(decoded(goal).canonicalPayload(),jdbc.queryForObject("SELECT canonical_payload FROM archiver.archived_event WHERE match_id=? AND event_id='goal'",String.class,match));
        assertEquals(2,receipts(match));
    }
    @Test void duplicateAtAnotherOffsetCreatesReceiptButNotAnotherEvent() {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);
        assertEquals(ArchiveOutcome.STORED,store.save(record(goal,1),decoded(goal)));
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(record(goal,2),decoded(goal)));
        assertEquals(1,store.find(goal.matchId(),0,10).totalElements());assertEquals(2,receipts(goal.matchId()));
    }
    @Test void identityConflictNeverOverwritesOriginalEvent() {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);var raw=record(goal,1);
        store.save(raw,decoded(goal));
        assertEquals(ArchiveOutcome.IDENTITY_CONFLICT,store.save(record(goal,2),DecodedInput.valid(goal,"changed-canonical")));
        assertEquals(decoded(goal).canonicalPayload(),jdbc.queryForObject("SELECT canonical_payload FROM archiver.archived_event WHERE match_id=?",String.class,goal.matchId()));
        assertArrayEquals(raw.value(),jdbc.queryForObject("SELECT original_payload FROM archiver.archived_event WHERE match_id=?",byte[].class,goal.matchId()));
        assertEquals("EVENT_ID_REUSED",jdbc.queryForObject("SELECT reason_code FROM archiver.input_receipt WHERE source_id=? AND record_offset=2",String.class,goal.matchId()));
    }
    @Test void sameEventIdAcrossMatchesRemainsIndependent() {
        var one=event(match(),"same",SportEvent.EventType.GOAL,null);var two=event(match(),"same",SportEvent.EventType.GOAL,null);
        assertEquals(ArchiveOutcome.STORED,store.save(record(one,1),decoded(one)));
        assertEquals(ArchiveOutcome.STORED,store.save(record(two,1),decoded(two)));
        assertEquals(1,store.find(one.matchId(),0,10).totalElements());assertEquals(1,store.find(two.matchId(),0,10).totalElements());
    }
    @Test void samePositionIsIdempotentAndChangedEvidenceIsRejected() {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);var raw=record(goal,1);store.save(raw,decoded(goal));
        assertEquals(ArchiveOutcome.STORED,store.save(raw,decoded(goal)));
        for(var changed:List.of(
                new InputRecord(raw.sourceId(),raw.topic(),0,1,1,new byte[]{9},raw.value(),raw.headers()),
                new InputRecord(raw.sourceId(),raw.topic(),0,1,1,raw.key(),new byte[]{9},raw.headers()),
                new InputRecord(raw.sourceId(),raw.topic(),0,1,2,raw.key(),raw.value(),raw.headers()),
                new InputRecord(raw.sourceId(),raw.topic(),0,1,1,raw.key(),raw.value(),"[{\"changed\":true}]")))
            assertThrows(IllegalStateException.class,()->store.save(changed,decoded(goal)));
        assertEquals(1,receipts(goal.matchId()));
    }
    @Test void malformedBytesAndTombstoneArePreservedWithoutEvent() {
        String source=match();byte[] bytes=new byte[]{0,(byte)255,17};
        var malformed=new InputRecord(source,"match_events",0,1,1,null,bytes,"[]");
        assertEquals(ArchiveOutcome.QUARANTINED,store.save(malformed,DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON)));
        assertArrayEquals(bytes,jdbc.queryForObject("SELECT original_payload FROM archiver.input_receipt WHERE source_id=?",byte[].class,source));
        var tombstone=new InputRecord(source,"match_events",0,2,1,null,null,"[]");
        assertEquals(ArchiveOutcome.QUARANTINED,store.save(tombstone,DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON)));
        assertNull(jdbc.queryForObject("SELECT original_payload FROM archiver.input_receipt WHERE source_id=? AND record_offset=2",byte[].class,source));
        assertEquals(0,store.find(source,0,10).totalElements());
    }
    @Test void failureAfterHibernateFlushRollsBackEventAndReceiptThenRetrySucceeds() {
        var actual=context.getBean(InputReceiptRepository.class);
        var failing=proxy(InputReceiptRepository.class,actual,(method,result)->{if(method.getName().equals("flush"))throw new IllegalStateException("Injected after real flush");});
        var broken=new JpaArchiveStore(context.getBean(ArchivedEventRepository.class),failing,context.getBean(PlatformTransactionManager.class));
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);
        assertThrows(IllegalStateException.class,()->broken.save(record(goal,1),decoded(goal)));
        assertEquals(0,receipts(goal.matchId()));assertEquals(0,store.find(goal.matchId(),0,10).totalElements());
        assertEquals(ArchiveOutcome.STORED,store.save(record(goal,1),decoded(goal)));
    }
    @Test void concurrentDuplicatesProduceOneEventAndAllReceipts() throws Exception {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
        try {
            var tasks=new ArrayList<Future<ArchiveOutcome>>();
            for(int i=0;i<12;i++){int offset=i;tasks.add(pool.submit(()->{start.await();return store.save(record(goal,offset),decoded(goal));}));}
            start.countDown();var results=new ArrayList<ArchiveOutcome>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(results,ArchiveOutcome.STORED));assertEquals(11,Collections.frequency(results,ArchiveOutcome.DUPLICATE));
            assertEquals(1,store.find(goal.matchId(),0,10).totalElements());assertEquals(12,receipts(goal.matchId()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM archiver.input_receipt WHERE source_id=? AND outcome='PENDING'",Integer.class,goal.matchId()));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentSameOffsetCreatesOnlyOneReceipt() throws Exception {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Future<ArchiveOutcome>>();
            for(int i=0;i<4;i++)tasks.add(pool.submit(()->{start.await();return store.save(record(goal,1),decoded(goal));}));
            start.countDown();for(var task:tasks)assertEquals(ArchiveOutcome.STORED,task.get(20,TimeUnit.SECONDS));
            assertEquals(1,receipts(goal.matchId()));assertEquals(1,store.find(goal.matchId(),0,10).totalElements());
        } finally {pool.shutdownNow();}
    }
    @Test void paginationUsesSequenceAndKeepsCountAndRowsInOneRepeatableReadSnapshot() {
        String match=match();var goal=event(match,"first",SportEvent.EventType.GOAL,null);store.save(record(goal,1),decoded(goal));
        var actual=context.getBean(ArchivedEventRepository.class);
        var repository=proxy(ArchivedEventRepository.class,actual,(method,result)->{
            if(method.getName().equals("countByMatchId")){
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals("repeatable read",jdbc.queryForObject("SHOW transaction_isolation",String.class));
                var later=event(match,"second",SportEvent.EventType.GOAL,null);
                CompletableFuture.runAsync(()->store.save(record(later,2),decoded(later))).join();
            }
        });
        var observed=new JpaArchiveStore(repository,context.getBean(InputReceiptRepository.class),context.getBean(PlatformTransactionManager.class));
        var consistent=observed.find(match,0,10);assertEquals(1,consistent.totalElements());assertEquals(1,consistent.content().size());
        assertEquals(2,store.find(match,0,10).totalElements());
        assertEquals("second",store.find(match,1,1).content().get(0).eventId());
        assertTrue(store.find(match,Integer.MAX_VALUE,200).content().isEmpty());
    }
    @Test void closingAndReopeningEntityManagerFactoryRecoversPersistedData() {
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);store.save(record(goal,1),decoded(goal));
        close();open();
        assertEquals(1,store.find(goal.matchId(),0,10).totalElements());
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(record(goal,2),decoded(goal)));
    }
    @Test void existingRuntimePrivilegesAreSufficientWithoutDdlDeleteEventUpdateOrLargeObjects() {
        var privileges=jdbc.queryForMap("""
            SELECT has_database_privilege(current_user,current_database(),'CREATE') AS db_create,
              has_schema_privilege(current_user,'archiver','CREATE') AS schema_create,
              has_table_privilege(current_user,'archiver.archived_event','UPDATE') AS event_update,
              has_table_privilege(current_user,'archiver.archived_event','DELETE') AS event_delete,
              has_table_privilege(current_user,'archiver.input_receipt','DELETE') AS receipt_delete,
              has_table_privilege(current_user,'archiver.input_receipt','UPDATE') AS receipt_update
            """);
        for(String field:List.of("db_create","schema_create","event_update","event_delete","receipt_delete"))assertEquals(false,privileges.get(field),field);
        assertEquals(true,privileges.get("receipt_update"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)",Integer.class));
    }
    @Test void jdbcFallbackAndJpaReadEachOthersCommittedHistoryWithTheSameManager() {
        var fallback=new JdbcArchiveStore(jdbc,new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
        var goal=event(match(),"goal",SportEvent.EventType.GOAL,null);
        assertEquals(ArchiveOutcome.STORED,fallback.save(record(goal,1),decoded(goal)));
        assertEquals(ArchiveOutcome.DUPLICATE,store.save(record(goal,2),decoded(goal)));
        assertEquals(fallback.find(goal.matchId(),0,10),store.find(goal.matchId(),0,10));
    }
    @Test void listenerReturnsOnlyAfterCommitAndPropagatesRealRollbackWithoutBrokerInteraction() {
        String match=match();String json="{\"event_id\":\"goal\",\"match_id\":\""+match+"\",\"event_type\":\"GOAL\",\"team_id\":\"home\",\"minute\":10,\"timestamp\":\"2026-11-17T00:00:00Z\"}";
        var listener=new ArchiveKafkaListener(new ArchiveService(store,new JsonEventDecoder()),match);
        var record=new org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>("match_events",0,1,match.getBytes(StandardCharsets.UTF_8),json.getBytes(StandardCharsets.UTF_8));
        listener.receive(record);assertEquals(1,receipts(match));
        // A failed transaction escapes the unchanged listener. No Kafka acknowledgements are mocked as real.
        var failing=proxy(InputReceiptRepository.class,context.getBean(InputReceiptRepository.class),(method,result)->{if(method.getName().equals("flush"))throw new IllegalStateException("Injected flush failure");});
        var broken=new JpaArchiveStore(context.getBean(ArchivedEventRepository.class),failing,context.getBean(PlatformTransactionManager.class));
        var failedListener=new ArchiveKafkaListener(new ArchiveService(broken,new JsonEventDecoder()),match);
        var next=new org.apache.kafka.clients.consumer.ConsumerRecord<byte[],byte[]>("match_events",0,2,match.getBytes(StandardCharsets.UTF_8),json.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->failedListener.receive(next));assertEquals(1,receipts(match));
    }
    @Test void productionBootWiringSelectsOneStoreAndManagerWithListenersOff() {
        bootStore(null,JpaArchiveStore.class);
        bootStore("jdbc",JdbcArchiveStore.class);
    }
    @Test void unsafeHibernateDdlIsRejectedBeforeSchemaModification() {
        assertThrows(Exception.class,()->bootStore("unsafe-create",JpaArchiveStore.class));
        assertEquals(originalSchema,fingerprint());
    }
    private void bootStore(String adapter,Class<?> expected) {
        var settings=new HashMap<String,Object>();
        settings.put("spring.datasource.url",System.getenv("DEV3_IT_DB_URL"));
        settings.put("spring.datasource.username",System.getenv("ARCHIVER_JPA_IT_APP_USERNAME"));
        settings.put("spring.datasource.password",System.getenv("ARCHIVER_JPA_IT_APP_PASSWORD"));
        settings.put("spring.flyway.enabled","false");settings.put("spring.sql.init.mode","never");
        settings.put("spring.kafka.admin.auto-create","false");settings.put("spring.kafka.bootstrap-servers","127.0.0.1:1");
        settings.put("archive.consumer.enabled","false");settings.put("spring.kafka.listener.auto-startup","false");
        if("unsafe-create".equals(adapter))settings.put("spring.jpa.hibernate.ddl-auto","create");
        else if(adapter!=null)settings.put("archive.storage.adapter",adapter);
        try(var boot=new org.springframework.boot.builder.SpringApplicationBuilder(com.live_jgv.archiver.HistoricalArchiverApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .initializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated-jpa-test",settings)))
                .run()) {
            assertEquals(1,boot.getBeansOfType(ArchiveStore.class).size());assertInstanceOf(expected,boot.getBean(ArchiveStore.class));
            assertEquals(1,boot.getBeansOfType(PlatformTransactionManager.class).size());
            assertInstanceOf(JpaTransactionManager.class,boot.getBean(PlatformTransactionManager.class));
            assertEquals("validate",boot.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("false",boot.getEnvironment().getProperty("spring.jpa.open-in-view"));
            assertFalse(boot.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class).getListenerContainer("historicalArchiveListener").isRunning());
            var goal=event(match(),"boot-goal",SportEvent.EventType.GOAL,null);
            assertEquals(ArchiveOutcome.STORED,boot.getBean(ArchiveStore.class).save(record(goal,1),decoded(goal)));
        }
    }
    private static String match(){return "jpa-"+UUID.randomUUID();}
    private static SportEvent event(String match,String id,SportEvent.EventType type,String reference){return new SportEvent(id,match,type,type==SportEvent.EventType.GOAL?"home":null,10,Instant.EPOCH,reference);}
    private static InputRecord record(SportEvent event,long offset){return new InputRecord(event.matchId(),"match_events",0,offset,1,event.matchId().getBytes(StandardCharsets.UTF_8),("original:"+event.eventId()).getBytes(StandardCharsets.UTF_8),"[]");}
    private static DecodedInput decoded(SportEvent event){return DecodedInput.valid(event,"canonical:"+event.eventId());}
    private int receipts(String source){return jdbc.queryForObject("SELECT count(*) FROM archiver.input_receipt WHERE source_id=?",Integer.class,source);}
    private interface Observation {void after(Method method,Object result);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,T delegate,Observation observer){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            try{Object result=method.invoke(delegate,args);observer.after(method,result);return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static List<Map<String,Object>> fingerprint(){
        return admin.queryForList("""
            SELECT 'column' AS kind,table_name||'.'||column_name AS name,data_type||':'||is_nullable||':'||COALESCE(column_default,'') AS definition
              FROM information_schema.columns WHERE table_schema='archiver'
            UNION ALL SELECT 'constraint',conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='archiver'::regnamespace
            UNION ALL SELECT 'index',indexname,indexdef FROM pg_indexes WHERE schemaname='archiver'
            UNION ALL SELECT 'owner',c.relname,pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON c.relnamespace=n.oid WHERE n.nspname='archiver'
            ORDER BY kind,name
            """);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableJpaRepositories(basePackageClasses=ArchivedEventRepository.class)
    static class JpaConfiguration {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source){
            var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
            factory.setPackagesToScan(ArchivedEventEntity.class.getPackageName());factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate","hibernate.jdbc.time_zone","UTC"));return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory){return new JpaTransactionManager(factory);}
    }
}
