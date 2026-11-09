package dev.payments.payment;

import dev.payments.contract.*;
import dev.payments.ledger.LedgerRpc;
import dev.payments.ledger.LedgerStore;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PaymentRecoveryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    HikariDataSource paymentDb,ledgerDb;
    JdbcTemplate payments,ledger;
    TransactionTemplate paymentTx;
    PaymentStore store;
    LedgerStore ledgerStore;
    Server server;
    LedgerClient client;

    @BeforeEach void setup() {
        paymentDb=database("payment");ledgerDb=database("ledger");
        payments=new JdbcTemplate(paymentDb);ledger=new JdbcTemplate(ledgerDb);
        paymentTx=new TransactionTemplate(new DataSourceTransactionManager(paymentDb));
        store=new PaymentStore(payments,paymentTx);
        ledgerStore=new LedgerStore(ledger,new TransactionTemplate(new DataSourceTransactionManager(ledgerDb)));
    }
    HikariDataSource database(String service) {
        String schema=service+"_"+UUID.randomUUID().toString().replace("-","");
        var ds=new HikariDataSource();
        String url=DB.getJdbcUrl();
        ds.setJdbcUrl(url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        ds.setUsername(DB.getUsername());ds.setPassword(DB.getPassword());ds.setMaximumPoolSize(12);
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/"+service).load().migrate();
        return ds;
    }
    @AfterEach void close() throws Exception {
        if(client!=null) client.close();
        if(server!=null) {server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);}
        paymentDb.close();ledgerDb.close();
    }
    Payment create() { return store.create(UUID.randomUUID().toString(),new CreatePayment("alice","merchant",2500L,"USD")).payment(); }
    void start(BindableService service,int port) throws Exception {
        server=NettyServerBuilder.forPort(port).addService(service).build().start();
        client=new LedgerClient("localhost",server.getPort(),500);
    }
    PaymentWorker worker() {return new PaymentWorker(payments,paymentTx,client);}
    void makeDue() {payments.update("UPDATE payments SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE status='PENDING'");}
    void assertOneJournal(Payment p) {
        assertThat(store.get(p.id()).status()).isEqualTo("COMPLETED");
        assertThat(store.get(p.id()).completedAt()).isNotNull();
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals",Long.class)).isEqualTo(1);
        assertThat(ledger.queryForObject("SELECT count(*) FROM entries",Long.class)).isEqualTo(2);
        assertThat(ledgerStore.get(p.id().toString()).getTransfer().getAmountMinor()).isEqualTo(2500);
    }

    @Test void unavailableLedgerKeepsPaymentDurableAndReplacementWorkerRecovers() throws Exception {
        int port;
        try(var socket=new ServerSocket(0)) {port=socket.getLocalPort();}
        client=new LedgerClient("localhost",port,200);
        var p=create();
        assertThat(worker().processOne()).isTrue();
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(payments.queryForObject("SELECT attempt_count FROM payments WHERE id=?",Integer.class,p.id())).isEqualTo(1);
        assertThat(worker().processOne()).isFalse(); // Retry backoff is durable, not a tight loop.
        client.close();start(new LedgerRpc(ledgerStore),port);
        makeDue();
        // Reopen the payment pool, as a restarted process would, against the same durable state.
        String url=paymentDb.getJdbcUrl();paymentDb.close();
        paymentDb=new HikariDataSource();paymentDb.setJdbcUrl(url);
        paymentDb.setUsername(DB.getUsername());paymentDb.setPassword(DB.getPassword());
        payments=new JdbcTemplate(paymentDb);
        paymentTx=new TransactionTemplate(new DataSourceTransactionManager(paymentDb));
        store=new PaymentStore(payments,paymentTx);
        assertThat(worker().processOne()).isTrue();
        assertOneJournal(p);
    }

    @Test void committedLedgerWithLostResponseIsReplayedWithoutDuplicates() throws Exception {
        var dropFirst=new AtomicBoolean(true);
        start(new LedgerGrpc.LedgerImplBase() {
            @Override public void postTransfer(Transfer request,StreamObserver<Journal> response) {
                var result=ledgerStore.post(request);
                if(dropFirst.getAndSet(false)) response.onError(Status.UNAVAILABLE.withDescription("response lost after commit").asRuntimeException());
                else {response.onNext(result);response.onCompleted();}
            }
        },0);
        var p=create();
        worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals",Long.class)).isEqualTo(1);
        makeDue();worker().processOne();
        assertOneJournal(p);
    }

    @Test void localCompletionFailureRollsBackThenRecoversExistingLedgerEffect() throws Exception {
        start(new LedgerRpc(ledgerStore),0);
        var p=create();
        payments.execute("CREATE FUNCTION fail_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected completion failure'; END $$");
        payments.execute("CREATE TRIGGER injected_failure BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION fail_completion()");
        assertThatThrownBy(()->worker().processOne()).isInstanceOf(RuntimeException.class);
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals",Long.class)).isEqualTo(1);
        payments.execute("DROP TRIGGER injected_failure ON payments");
        worker().processOne();assertOneJournal(p);
    }

    @Test void competingWorkersSkipLockedPaymentAndCreateOneLedgerEffect() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        start(new LedgerGrpc.LedgerImplBase() {
            @Override public void postTransfer(Transfer request,StreamObserver<Journal> response) {
                entered.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test coordination timeout");
                    response.onNext(ledgerStore.post(request));response.onCompleted();
                } catch(Exception e) { response.onError(Status.INTERNAL.asRuntimeException()); }
            }
        },0);
        // Longer deadline permits deterministic coordination without racing a short RPC timeout.
        client.close();client=new LedgerClient("localhost",server.getPort(),5000);
        var p=create();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->worker().processOne());
            try {
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(()->worker().processOne()).get(3,TimeUnit.SECONDS)).isFalse();
            } finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertOneJournal(p);
    }
}
