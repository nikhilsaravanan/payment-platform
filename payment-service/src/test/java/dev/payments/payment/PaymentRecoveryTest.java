package dev.payments.payment;

import dev.payments.contract.*;
import dev.payments.ledger.LedgerRpc;
import dev.payments.ledger.LedgerStore;
import dev.payments.ledger.AccountStore;
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
        var accounts=new AccountStore(ledger);accounts.create("alice","USD");accounts.create("merchant","USD");
        ledgerStore.fund(FundingRequest.newBuilder().setIdempotencyKey("fixture-funding").setAccountId("alice")
                .setAmountMinor(1000000).setCurrency("USD").build());
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
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?",Long.class,p.id())).isEqualTo(1);
        assertThat(ledger.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?",Long.class,p.id())).isEqualTo(2);
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
        client.close();start(new LedgerRpc(ledgerStore,new AccountStore(ledger)),port);
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
            @Override public void submitTransfer(Transfer request,StreamObserver<TransferResult> response) {
                var result=ledgerStore.submit(request);
                if(dropFirst.getAndSet(false)) response.onError(Status.UNAVAILABLE.withDescription("response lost after commit").asRuntimeException());
                else {response.onNext(result);response.onCompleted();}
            }
        },0);
        var p=create();
        worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?",Long.class,p.id())).isEqualTo(1);
        makeDue();worker().processOne();
        assertOneJournal(p);
    }

    @Test void localCompletionFailureRollsBackThenRecoversExistingLedgerEffect() throws Exception {
        start(new LedgerRpc(ledgerStore,new AccountStore(ledger)),0);
        var p=create();
        payments.execute("CREATE FUNCTION fail_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected completion failure'; END $$");
        payments.execute("CREATE TRIGGER injected_failure BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION fail_completion()");
        assertThatThrownBy(()->worker().processOne()).isInstanceOf(RuntimeException.class);
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?",Long.class,p.id())).isEqualTo(1);
        payments.execute("DROP TRIGGER injected_failure ON payments");
        worker().processOne();assertOneJournal(p);
    }

    @Test void competingWorkersSkipLockedPaymentAndCreateOneLedgerEffect() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        start(new LedgerGrpc.LedgerImplBase() {
            @Override public void submitTransfer(Transfer request,StreamObserver<TransferResult> response) {
                entered.countDown();
                try {
                    if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test coordination timeout");
                    response.onNext(ledgerStore.submit(request));response.onCompleted();
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

    @Test void lostRejectionResponseRemainsRejectedAfterFunding() throws Exception {
        new AccountStore(ledger).create("empty","USD");
        var drop=new AtomicBoolean(true);
        start(new LedgerGrpc.LedgerImplBase() {
            @Override public void submitTransfer(Transfer request,StreamObserver<TransferResult> response) {
                var result=ledgerStore.submit(request);
                if(drop.getAndSet(false)) response.onError(Status.UNAVAILABLE.asRuntimeException());
                else {response.onNext(result);response.onCompleted();}
            }
        },0);
        var p=store.create("rejected-payment",new CreatePayment("empty","merchant",500L,"USD")).payment();
        worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(ledger.queryForObject("SELECT outcome FROM transfer_decisions WHERE payment_id=?",String.class,p.id())).isEqualTo("INSUFFICIENT_FUNDS");
        ledgerStore.fund(FundingRequest.newBuilder().setIdempotencyKey("later-funding").setAccountId("empty").setAmountMinor(1000).setCurrency("USD").build());
        makeDue();worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("REJECTED");
        assertThat(store.get(p.id()).rejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(store.get(p.id()).rejectedAt()).isNotNull();
        assertThat(store.get(p.id()).completedAt()).isNull();
        assertThat(new AccountStore(ledger).get("empty").getBalanceMinor()).isEqualTo("1000");
        assertThat(ledger.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?",Long.class,p.id())).isZero();
        assertThat(worker().processOne()).isFalse();
    }

    @Test void missingAccountIsTerminalAndSameKeyStillReturnsRejectedPayment() throws Exception {
        start(new LedgerRpc(ledgerStore,new AccountStore(ledger)),0);
        var body=new CreatePayment("alice","missing",500L,"USD");
        var p=store.create("missing-account-payment",body).payment();
        worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("REJECTED");
        assertThat(store.get(p.id()).rejectionReason()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(store.create("missing-account-payment",body).payment()).isEqualTo(store.get(p.id()));
        assertThat(worker().processOne()).isFalse();
    }

    @Test void unknownLedgerOutcomeStaysPending() throws Exception { assertMalformedOutcomeRetries(false); }
    @Test void responseForAnotherPaymentStaysPending() throws Exception { assertMalformedOutcomeRetries(true); }

    void assertMalformedOutcomeRetries(boolean wrongId) throws Exception {
        start(new LedgerGrpc.LedgerImplBase() {
            @Override public void submitTransfer(Transfer request,StreamObserver<TransferResult> response) {
                response.onNext(TransferResult.newBuilder().setPaymentId(wrongId?UUID.randomUUID().toString():request.getPaymentId())
                        .setOutcome(wrongId?TransferOutcome.POSTED:TransferOutcome.TRANSFER_OUTCOME_UNSPECIFIED).build());
                response.onCompleted();
            }
        },0);
        var p=create();worker().processOne();
        assertThat(store.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(payments.queryForObject("SELECT last_error FROM payments WHERE id=?",String.class,p.id())).isEqualTo("DATA_LOSS");
    }
}
