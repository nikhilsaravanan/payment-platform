package dev.payments.payment.webhooks;

import dev.payments.payment.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"payments.worker.enabled=false","webhooks.worker.enabled=false","webhooks.allowed-urls=http://localhost:9999/hooks"})
class WebhookOutboxTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired WebhookSubscriptions subscriptions;
    @Autowired PaymentStore payments;
    @Autowired RefundStore refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    static final String URL="http://localhost:9999/hooks",SECRET="test-key-"+"a".repeat(40);
    @BeforeEach void reset() {jdbc.execute("TRUNCATE webhook_attempts,webhook_deliveries,webhook_events,webhook_subscriptions,refunds,payments CASCADE");}
    WebhookSubscriptions.Created subscribe(String key){return subscriptions.create(key,"merchant",URL,SECRET);}
    Payment pending(){return payments.create("internal-"+UUID.randomUUID(),new CreatePayment("payer","merchant",1000L,"USD")).payment();}
    void complete(Payment p){jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",p.id());}

    @Test void terminalTransitionAtomicallyCreatesOneFrozenEventAndFanout() throws Exception {
        var sub=subscribe("subscriber").subscription();var p=pending();complete(p);
        String payload=jdbc.queryForObject("SELECT payload FROM webhook_events WHERE resource_id=?",String.class,p.id());
        var event=json.readTree(payload);
        assertThat(event.path("type").asText()).isEqualTo("payment.completed");assertThat(event.path("accountId").asText()).isEqualTo("merchant");
        assertThat(event.path("data").path("id").asText()).isEqualTo(p.id().toString());assertThat(event.path("data").path("amountMinor").asLong()).isEqualTo(1000);
        assertThat(event.path("data").path("status").asText()).isEqualTo("COMPLETED");assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload).doesNotContain(SECRET,"idempotency_key","attempt_count");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE subscription_id=?",Long.class,sub.id())).isEqualTo(1);
        jdbc.update("UPDATE payments SET status='COMPLETED' WHERE id=?",p.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE resource_id=?",Long.class,p.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM webhook_events WHERE resource_id=?",String.class,p.id())).isEqualTo(payload);
    }

    @Test void refundOutcomeRoutesToOriginalMerchantAndDoesNotBackfillOldEvents() throws Exception {
        var p=pending();complete(p);var sub=subscribe("late-subscriber").subscription();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries",Long.class)).isZero();
        var r=refunds.create(p.id(),"refund-request",100).refund();
        jdbc.update("UPDATE refunds SET status='REJECTED',rejected_at=clock_timestamp(),rejection_reason='INSUFFICIENT_FUNDS' WHERE id=?",r.id());
        var payload=json.readTree(jdbc.queryForObject("SELECT payload FROM webhook_events WHERE resource_id=?",String.class,r.id()));
        assertThat(payload.path("type").asText()).isEqualTo("refund.rejected");assertThat(payload.path("accountId").asText()).isEqualTo("merchant");
        assertThat(payload.path("data").path("paymentId").asText()).isEqualTo(p.id().toString());
        assertThat(payload.path("data").path("rejectionReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE subscription_id=?",Long.class,sub.id())).isEqualTo(1);
    }

    @Test void rollbackLeavesNoTerminalStateEventOrDelivery() {
        subscribe("rollback-subscriber");var p=pending();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{complete(p);throw new IllegalStateException("simulated rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(payments.get(p.id()).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries",Long.class)).isZero();
        complete(p);assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events",Long.class)).isEqualTo(1);
    }

    @Test void disableCancelsQueuedWorkAndStopsFutureFanoutWithoutDeletingAudit() {
        var sub=subscribe("disable-subscriber").subscription();complete(pending());subscriptions.disable(sub.id());subscriptions.disable(sub.id());
        assertThat(subscriptions.get(sub.id()).active()).isFalse();assertThat(subscriptions.get(sub.id()).disabledAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE subscription_id=?",String.class,sub.id())).isEqualTo("CANCELED");
        complete(pending());assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events",Long.class)).isEqualTo(2);
        assertThat(subscribe("disable-subscriber").subscription().active()).isFalse();
    }

    @Test void eventPayloadAndSubscriptionIdentityCannotBeRewritten() {
        var sub=subscribe("immutable-subscriber").subscription();complete(pending());
        assertThatThrownBy(()->jdbc.update("UPDATE webhook_events SET payload='{}'")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM webhook_events")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE webhook_subscriptions SET signing_secret=? WHERE id=?","x".repeat(64),sub.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->subscriptions.create("immutable-subscriber","merchant",URL,"b".repeat(64))).isInstanceOf(WebhookSubscriptions.Conflict.class);
    }

    @Test void concurrentSubscriptionReplayCreatesOneSubscription() throws Exception {
        try(var pool=Executors.newFixedThreadPool(12)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<WebhookSubscriptions.Created>>();
            for(int i=0;i<24;i++)futures.add(pool.submit(()->{start.await();return subscribe("concurrent-subscriber");}));
            start.countDown();var ids=new HashSet<UUID>();int created=0;
            for(var f:futures){var result=f.get(20,TimeUnit.SECONDS);ids.add(result.subscription().id());if(result.inserted())created++;}
            assertThat(created).isEqualTo(1);assertThat(ids).hasSize(1);
        }
    }

    @Test void registrationReplayDoesNotDependOnCurrentTargetPolicy() {
        var original=subscribe("policy-replay").subscription();
        var changedPolicy=new WebhookSubscriptions(jdbc,tx,new WebhookTargets(""));
        assertThat(changedPolicy.create("policy-replay","merchant",URL,SECRET).subscription()).isEqualTo(original);
        assertThatThrownBy(()->changedPolicy.create("new-policy-key","merchant",URL,SECRET)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void disableSerializesWithUncommittedFanout() throws Exception {
        var sub=subscribe("fanout-race").subscription();var p=pending();
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var fanout=pool.submit(()->tx.executeWithoutResult(s->{complete(p);inserted.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            assertThat(inserted.await(3,TimeUnit.SECONDS)).isTrue();
            var disabling=pool.submit(()->subscriptions.disable(sub.id()));
            try {assertThatThrownBy(()->disabling.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            fanout.get(5,TimeUnit.SECONDS);disabling.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE subscription_id=?",String.class,sub.id())).isEqualTo("CANCELED");
        }
    }
}
