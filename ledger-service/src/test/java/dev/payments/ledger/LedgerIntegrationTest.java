package dev.payments.ledger;

import dev.payments.contract.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import java.util.concurrent.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = "ledger.grpc.port=0")
class LedgerIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired LedgerStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired GrpcServer grpc;
    @Autowired AccountStore accounts;
    @Autowired LedgerRefundStore refunds;
    @Autowired LedgerHistoryStore history;

    @BeforeEach void provisionFunds() {
        accounts.create("alice","USD");accounts.create("merchant","USD");
        store.fund(FundingRequest.newBuilder().setIdempotencyKey(UUID.randomUUID().toString())
                .setAccountId("alice").setAmountMinor(1000000).setCurrency("USD").build());
    }

    @Test void applicationStartsGrpcListener() {
        assertThat(grpc.port()).isPositive();
    }

    @Test void standaloneProcessStaysAliveAfterStartup(@TempDir Path temp) throws Exception {
        Path output=temp.resolve("ledger.log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                LedgerApplication.class.getName(),"--spring.datasource.url="+DB.getJdbcUrl(),
                "--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),
                "--ledger.grpc.port=0").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            await().atMost(Duration.ofSeconds(20)).until(()->Files.readString(output).contains("Started LedgerApplication") || !process.isAlive());
            assertThat(process.waitFor(1,TimeUnit.SECONDS)).withFailMessage(Files.readString(output)).isFalse();
            assertThat(Files.readString(output)).contains("Started LedgerApplication");
        } finally {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
    }

    static Transfer transfer() {
        return Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString())
                .setSourceAccount("alice").setDestinationAccount("merchant")
                .setAmountMinor(2500).setCurrency("USD").build();
    }

    @Test void postsBalancedImmutableEntriesAndReplays() {
        var t = transfer();
        var journal = store.post(t);
        assertThat(journal.getEntriesList()).containsExactly(
                Entry.newBuilder().setAccount("alice").setSide("DEBIT").setAmountMinor(2500).setCurrency("USD").build(),
                Entry.newBuilder().setAccount("merchant").setSide("CREDIT").setAmountMinor(2500).setCurrency("USD").build());
        assertThat(store.post(t)).isEqualTo(journal);
        assertThat(store.get(t.getPaymentId())).isEqualTo(journal);
        assertThatThrownBy(() -> store.post(t.toBuilder().setAmountMinor(2501).build()))
                .isInstanceOf(LedgerStore.Conflict.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE entries SET amount_minor=3000 WHERE payment_id=?::uuid", t.getPaymentId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journals WHERE payment_id=?::uuid", t.getPaymentId())).isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentPostingHasOneJournalAndTwoEntries() throws Exception {
        var t = transfer();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Journal>>();
            for (int i=0; i<24; i++) futures.add(executor.submit(() -> { start.await(); return store.post(t); }));
            start.countDown();
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(store.get(t.getPaymentId()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?::uuid", Long.class, t.getPaymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?::uuid", Long.class, t.getPaymentId())).isEqualTo(2);
    }

    @Test void rejectsMissingUnbalancedAndMismatchedEntriesAtCommit() {
        for (int mode=0; mode<3; mode++) {
            var t=transfer();
            final int variant=mode;
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                insertJournal(t);
                if (variant>0) {
                    jdbc.update("INSERT INTO entries VALUES (?::uuid,'DEBIT','alice',2500,'USD')", t.getPaymentId());
                    jdbc.update("INSERT INTO entries VALUES (?::uuid,'CREDIT',?,?, 'USD')", t.getPaymentId(),
                            variant==2 ? "wrong-account" : "merchant", variant==1 ? 2400 : 2500);
                }
            })).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?::uuid", Long.class, t.getPaymentId())).isZero();
        }
    }

    @Test void secondEntryFailureRollsBackEntireJournal() {
        var t=transfer();
        // A database fault at the real second insert exercises the actual store transaction.
        jdbc.execute("CREATE FUNCTION fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.side='CREDIT' THEN RAISE EXCEPTION 'injected write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER injected_failure BEFORE INSERT ON entries FOR EACH ROW EXECUTE FUNCTION fail_credit()");
        try {
            assertThatThrownBy(() -> store.post(t)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE payment_id=?::uuid", Long.class, t.getPaymentId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?::uuid", Long.class, t.getPaymentId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER injected_failure ON entries");
            jdbc.execute("DROP FUNCTION fail_credit()");
        }
        assertThat(store.post(t).getEntriesCount()).isEqualTo(2);
    }

    @Test void rejectsInvalidTransfersBeforeStorage() {
        var t=transfer();
        for (var invalid : List.of(t.toBuilder().setAmountMinor(0).build(), t.toBuilder().setCurrency("EUR").build(),
                t.toBuilder().setDestinationAccount("alice").build(), t.toBuilder().setPaymentId("bad").build())) {
            assertThatThrownBy(() -> store.post(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void grpcReturnsJournalAndMapsErrorsWithoutLeakingDatabaseDetails() throws Exception {
        var server=NettyServerBuilder.forPort(0).addService(new LedgerRpc(store,accounts,refunds,history)).build().start();
        var channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();
        try {
            var client=LedgerGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
            var t=transfer();
            assertThat(client.postTransfer(t).getEntriesCount()).isEqualTo(2);
            assertThat(client.getTransfer(TransferId.newBuilder().setPaymentId(t.getPaymentId()).build()).getTransfer()).isEqualTo(t);
            assertThatThrownBy(()->client.postTransfer(t.toBuilder().setAmountMinor(2600).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS));
            assertThatThrownBy(()->client.postTransfer(t.toBuilder().setAmountMinor(0).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            assertThatThrownBy(()->client.getTransfer(TransferId.newBuilder().setPaymentId(UUID.randomUUID().toString()).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        } finally { channel.shutdownNow();server.shutdownNow(); }
    }

    private void insertJournal(Transfer t) {
        jdbc.update("INSERT INTO journals(payment_id,source_account,destination_account,amount_minor,currency) VALUES (?::uuid,?,?,?,?)",
                t.getPaymentId(),t.getSourceAccount(),t.getDestinationAccount(),t.getAmountMinor(),t.getCurrency());
    }
}
