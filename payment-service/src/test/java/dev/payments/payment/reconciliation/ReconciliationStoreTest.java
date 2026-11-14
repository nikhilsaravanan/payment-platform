package dev.payments.payment.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static dev.payments.payment.reconciliation.ReconciliationRequest.*;

@Testcontainers
class ReconciliationStoreTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static final Instant FROM=Instant.parse("2026-01-01T00:00:00Z"),TO=FROM.plusSeconds(86400);
    HikariDataSource ds;JdbcTemplate jdbc;TransactionTemplate tx;ReconciliationStore store;
    @BeforeEach void setup(){open(DB.getJdbcUrl()+"&currentSchema=r_"+UUID.randomUUID().toString().replace("-",""));Flyway.configure().dataSource(ds).schemas(schema()).locations("classpath:db/payment").load().migrate();}
    String schema(){return ds.getJdbcUrl().split("currentSchema=")[1];}
    void open(String url){ds=new HikariDataSource();ds.setJdbcUrl(url);ds.setUsername(DB.getUsername());ds.setPassword(DB.getPassword());ds.setMaximumPoolSize(24);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        store=new ReconciliationStore(jdbc,tx,new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));}
    @AfterEach void close(){ds.close();}
    UUID payment(String merchant,Instant created,String status){var id=UUID.randomUUID();jdbc.update("INSERT INTO payments(id,idempotency_key,source_account,destination_account,amount_minor,currency,created_at) VALUES (?,?, 'payer',?,2500,'USD',?)",id,id.toString(),merchant,Timestamp.from(created));
        if(status.equals("COMPLETED"))jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",id);return id;}
    ReconciliationRequest request(ProcessorRecord...rows){return new ReconciliationRequest("merchant",FROM,TO,List.of(rows));}
    ProcessorRecord row(OperationType type,UUID id){return new ProcessorRecord(id.toString(),type,id,2500L,"USD");}

    @Test void snapshotsRefundMerchantAndClosedWindowAndPersistsOrderedEvidence(){
        var p=payment("merchant",FROM.minusSeconds(1),"COMPLETED");var refund=UUID.randomUUID();
        jdbc.update("INSERT INTO refunds(id,idempotency_key,payment_id,amount_minor,currency,created_at) VALUES (?,?,?,2500,'USD',?)",refund,refund.toString(),p,Timestamp.from(FROM));
        jdbc.update("UPDATE refunds SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",refund);
        var end=payment("merchant",TO,"COMPLETED");var other=payment("other",FROM,"COMPLETED");
        var result=store.create("scope",request(row(OperationType.REFUND,refund),row(OperationType.PAYMENT,end),row(OperationType.PAYMENT,other)));
        assertThat(result.inserted()).isTrue();assertThat(result.report().internalCount()).isEqualTo(1);assertThat(result.report().matchedCount()).isEqualTo(1);
        assertThat(store.findings(result.report().id(),20,0).items()).extracting(ReconciliationEngine.Finding::code).containsExactly("OUT_OF_SCOPE","OUT_OF_SCOPE");
        assertThat(store.evidence(result.report().id()).internalSnapshot()).hasSize(3);
        assertThat(result.report().capturedAt()).isBeforeOrEqualTo(Instant.now());
    }
    @Test void replayAndEvidenceStayFrozenAfterPendingPaymentCompletes(){
        var id=payment("merchant",FROM,"PENDING");var body=request(row(OperationType.PAYMENT,id));var first=store.create("frozen",body).report();
        jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",id);
        assertThat(store.create("frozen",body).report()).isEqualTo(first);
        assertThat(store.evidence(first.id()).internalSnapshot().getFirst().status()).isEqualTo("PENDING");
        assertThat(store.findings(first.id(),20,0).items().getFirst().code()).isEqualTo("INTERNAL_NOT_COMPLETED");
        var later=store.create("after-recovery",body).report();assertThat(later.matchedCount()).isEqualTo(1);assertThat(later.findingCount()).isZero();
    }
    @Test void concurrentIdenticalImportsCreateExactlyOneDurableReport() throws Exception {
        var body=request();try(var pool=Executors.newFixedThreadPool(24)){
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<ReconciliationStore.Created>>();
            for(int i=0;i<24;i++)jobs.add(pool.submit(()->{start.await();return store.create("same-key",body);}));start.countDown();
            var ids=new HashSet<UUID>();int inserted=0;for(var job:jobs){var result=job.get(20,TimeUnit.SECONDS);ids.add(result.report().id());if(result.inserted())inserted++;}
            assertThat(ids).hasSize(1);assertThat(inserted).isEqualTo(1);
        }assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Long.class)).isEqualTo(1);
    }
    @Test void concurrentChangedImportsConflictWithoutChangingWinner() throws Exception {
        try(var pool=Executors.newFixedThreadPool(12)){
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<String>>();
            for(int i=0;i<24;i++){var body=i%2==0?request():new ReconciliationRequest("other",FROM,TO,List.of());jobs.add(pool.submit(()->{start.await();try{return store.create("contested",body).report().accountId();}catch(ReconciliationStore.Conflict e){return "conflict";}}));}
            start.countDown();var results=new ArrayList<String>();for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(12);assertThat(new HashSet<>(results)).hasSize(2);
        }
    }
    @Test void failedFindingInsertRollsBackEntireReportAndReopenedPoolCanRetry(){
        payment("merchant",FROM,"COMPLETED");
        jdbc.execute("CREATE FUNCTION fail_finding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected report write failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_finding BEFORE INSERT ON reconciliation_findings FOR EACH ROW EXECUTE FUNCTION fail_finding()");
        assertThatThrownBy(()->store.create("recover",request())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Long.class)).isZero();
        jdbc.execute("DROP TRIGGER fail_finding ON reconciliation_findings");String url=ds.getJdbcUrl();ds.close();open(url);
        var recovered=store.create("recover",request()).report();assertThat(recovered.findingCount()).isEqualTo(1);
        assertThat(store.create("recover",request()).inserted()).isFalse();
    }
    @Test void paginationHasStableOrdinalBoundariesAndAuditCannotBeMutated(){
        var a=payment("merchant",FROM,"COMPLETED");payment("merchant",FROM,"COMPLETED");
        var result=store.create("pagination",request(new ProcessorRecord("wrong",OperationType.PAYMENT,a,2499L,"EUR"))).report();
        var first=store.findings(result.id(),2,0);assertThat(first.items()).hasSize(2);assertThat(first.nextAfter()).isEqualTo(2);
        var last=store.findings(result.id(),2,first.nextAfter());assertThat(last.items()).hasSize(1);assertThat(last.nextAfter()).isNull();
        assertThat(last.items().getFirst().ordinal()).isEqualTo(3);
        assertThatThrownBy(()->jdbc.update("UPDATE reconciliation_reports SET matched_count=0 WHERE id=?",result.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM reconciliation_findings WHERE report_id=?",result.id())).isInstanceOf(RuntimeException.class);
    }
    @Test void emptyScopeRetainsCaptureTimeAndOversizedScopeDoesNotPersistAReport(){
        var empty=store.create("empty",request()).report();assertThat(empty.capturedAt()).isNotNull();assertThat(empty.internalCount()).isZero();
        jdbc.update("INSERT INTO payments(id,idempotency_key,source_account,destination_account,amount_minor,currency,created_at) SELECT gen_random_uuid(),'bulk-'||n,'payer','merchant',1,'USD',? FROM generate_series(1,1001) n",Timestamp.from(FROM));
        assertThatThrownBy(()->store.create("too-large",request())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.create("empty",request()).report()).isEqualTo(empty);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Long.class)).isEqualTo(1);
    }
    @Test void importedIdsOnlyLookUpTheClaimedOperationType(){
        var payment=payment("other",FROM,"COMPLETED");
        var report=store.create("typed-lookup",request(row(OperationType.REFUND,payment))).report();
        assertThat(store.findings(report.id(),20,0).items().getFirst().code()).isEqualTo("MISSING_INTERNAL");
        assertThat(store.evidence(report.id()).internalSnapshot()).isEmpty();
    }
    @Test void uncommittedCompletionIsNotReadAndNewReportSeesLaterCommit() throws Exception {
        var id=payment("merchant",FROM,"PENDING");var updated=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var writer=pool.submit(()->tx.executeWithoutResult(s->{jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",id);updated.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            try {assertThat(updated.await(3,TimeUnit.SECONDS)).isTrue();var report=store.create("before-commit",request(row(OperationType.PAYMENT,id))).report();
                assertThat(store.findings(report.id(),20,0).items().getFirst().code()).isEqualTo("INTERNAL_NOT_COMPLETED");}
            finally {release.countDown();}writer.get(5,TimeUnit.SECONDS);
            assertThat(store.create("after-commit",request(row(OperationType.PAYMENT,id))).report().matchedCount()).isEqualTo(1);
        }
    }
}
