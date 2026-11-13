package dev.payments.payment.webhooks;

import com.sun.net.httpserver.*;
import dev.payments.payment.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class WebhookDeliveryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static final String SECRET="fixture-signing-secret-"+"a".repeat(40);
    HttpServer server;ExecutorService handlers;
    final AtomicReference<String> mode=new AtomicReference<>("OK");
    final AtomicInteger otherRequests=new AtomicInteger(),effects=new AtomicInteger();
    final Map<String,String> receipts=new ConcurrentHashMap<>();
    record Received(Headers headers,byte[] body) {}
    final BlockingQueue<Received> received=new LinkedBlockingQueue<>();
    JdbcTemplate jdbc;TransactionTemplate tx;WebhookSubscriptions subscriptions;WebhookDeliveryStore deliveries;
    WebhookHttp transport;String url;UUID subscription,delivery;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        server.createContext("/hooks",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();received.add(new Received(exchange.getRequestHeaders(),body));
            String id=exchange.getRequestHeaders().getFirst("Webhook-Id");
            if(receipts.putIfAbsent(id,new String(body,StandardCharsets.UTF_8))==null)effects.incrementAndGet();
            String behavior=mode.get();
            if(behavior.equals("DROP")){mode.set("OK");exchange.sendResponseHeaders(200,10);exchange.getResponseBody().write(1);exchange.close();}
            else if(behavior.equals("REDIRECT")){exchange.getResponseHeaders().set("Location",url.replace("/hooks","/other"));exchange.sendResponseHeaders(302,-1);exchange.close();}
            else if(behavior.equals("SLOW")){
                exchange.sendResponseHeaders(200,10);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();
                try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();
            } else {exchange.sendResponseHeaders(behavior.equals("ERROR")?503:204,-1);exchange.close();}
        });
        server.createContext("/other",exchange->{otherRequests.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        url="http://127.0.0.1:"+server.getAddress().getPort()+"/hooks";
        String schema="delivery_"+UUID.randomUUID().toString().replace("-","");String dbUrl=DB.getJdbcUrl();
        var ds=new DriverManagerDataSource(dbUrl+(dbUrl.contains("?")?"&":"?")+"currentSchema="+schema,DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/payment").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var targets=new WebhookTargets(url);subscriptions=new WebhookSubscriptions(jdbc,tx,targets);deliveries=new WebhookDeliveryStore(jdbc,tx);
        subscription=subscriptions.create("subscription","merchant",url,SECRET).subscription().id();
        var p=new PaymentStore(jdbc,tx).create("payment",new CreatePayment("payer","merchant",100L,"USD")).payment();
        jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp() WHERE id=?",p.id());
        delivery=deliveries.list(subscription,20).getFirst().id();transport=new WebhookHttp(targets,250);
    }
    @AfterEach void stop(){if(transport!=null)transport.close();if(server!=null)server.stop(0);if(handlers!=null)handlers.shutdownNow();}
    WebhookWorker worker(int max){return new WebhookWorker(deliveries,transport,max,2000);}
    void due(){jdbc.update("UPDATE webhook_deliveries SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=? AND status='PENDING'",delivery);}
    void expire(){jdbc.update("UPDATE webhook_deliveries SET lease_until=clock_timestamp()-interval '1 second' WHERE id=? AND status='IN_FLIGHT'",delivery);}

    @Test void sendsExactSignedSnapshotAndRecordsSuccess() throws Exception {
        assertThat(worker(3).processOne()).isTrue();var record=deliveries.get(delivery);
        assertThat(record.status()).isEqualTo("DELIVERED");assertThat(record.attemptCount()).isEqualTo(1);
        var request=received.poll(2,TimeUnit.SECONDS);assertThat(request).isNotNull();
        assertThat(new String(request.body(),StandardCharsets.UTF_8)).isEqualTo(deliveries.event(record.eventId()));
        assertThat(request.headers().getFirst("Webhook-Id")).isEqualTo(record.eventId().toString());
        assertThat(request.headers().getFirst("Webhook-Delivery-Id")).isEqualTo(delivery.toString());
        assertThat(request.headers().getFirst("Webhook-Subscription-Id")).isEqualTo(subscription.toString());
        String signature=request.headers().getFirst("Webhook-Signature");long timestamp=Long.parseLong(signature.split(",")[0].substring(2));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));String expected=HexFormat.of().formatHex(mac.doFinal(request.body()));
        assertThat(signature).isEqualTo("t="+timestamp+",v1="+expected);
        assertThat(deliveries.attempts(delivery).getFirst().outcome()).isEqualTo("SUCCEEDED");assertThat(worker(3).processOne()).isFalse();
    }

    @Test void retries503WithSameEventIdAndPayloadThenSucceeds() throws Exception {
        mode.set("ERROR");worker(3).processOne();var first=received.take();
        assertThat(deliveries.get(delivery).status()).isEqualTo("PENDING");assertThat(deliveries.get(delivery).lastHttpStatus()).isEqualTo(503);
        assertThat(worker(3).processOne()).isFalse();due();mode.set("OK");new WebhookWorker(new WebhookDeliveryStore(jdbc,tx),transport,3,2000).processOne();
        var second=received.take();assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().getFirst("Webhook-Id")).isEqualTo(first.headers().getFirst("Webhook-Id"));
        assertThat(deliveries.get(delivery).status()).isEqualTo("DELIVERED");assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(2);
    }

    @Test void lostAcknowledgmentReplaysOneBusinessEffect() {
        mode.set("DROP");worker(3).processOne();assertThat(deliveries.get(delivery).status()).isEqualTo("PENDING");
        due();worker(3).processOne();assertThat(deliveries.get(delivery).status()).isEqualTo("DELIVERED");
        assertThat(effects.get()).isEqualTo(1);assertThat(received).hasSize(2);
    }

    @Test void attemptBudgetIsDurableAndStopsFurtherRequests() {
        mode.set("ERROR");worker(2).processOne();due();worker(2).processOne();
        assertThat(deliveries.get(delivery).status()).isEqualTo("FAILED");assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(2);
        assertThat(worker(2).processOne()).isFalse();assertThat(received).hasSize(2);assertThat(deliveries.attempts(delivery)).hasSize(2);
    }

    @Test void expiredLeaseConsumesBudgetAndStaleTokenCannotOverwriteSuccess() {
        var first=deliveries.claim(3,2000).attempt();assertThat(first).isNotNull();assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(1);
        expire();var second=deliveries.claim(3,2000).attempt();
        assertThat(second.token()).isNotEqualTo(first.token());assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(2);
        assertThat(deliveries.complete(first,WebhookDeliveryStore.Result.success(204),3)).isFalse();
        assertThat(deliveries.complete(second,WebhookDeliveryStore.Result.success(204),3)).isTrue();
        assertThat(deliveries.complete(first,WebhookDeliveryStore.Result.httpError(503),3)).isFalse();
        assertThat(deliveries.get(delivery).status()).isEqualTo("DELIVERED");
        assertThat(deliveries.attempts(delivery).stream().map(WebhookDeliveryStore.AttemptView::outcome).toList()).containsExactly("ABANDONED","SUCCEEDED");
    }

    @Test void expiredFinalReservationFailsWithoutAnotherHttpCall() {
        deliveries.claim(1,2000);expire();assertThat(worker(1).processOne()).isTrue();
        assertThat(deliveries.get(delivery).status()).isEqualTo("FAILED");assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(1);
        assertThat(received).isEmpty();assertThat(deliveries.attempts(delivery).getFirst().outcome()).isEqualTo("ABANDONED");
    }

    @Test void independentWorkersClaimOneReservation() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<WebhookDeliveryStore.Claim>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return new WebhookDeliveryStore(jdbc,tx).claim(3,2000);}));
            start.countDown();int claims=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS).attempt()!=null)claims++;
            assertThat(claims).isEqualTo(1);assertThat(deliveries.get(delivery).attemptCount()).isEqualTo(1);
        }
    }

    @Test void disableCancelsQueuedOrFailedInFlightWork() {
        var attempt=deliveries.claim(3,2000).attempt();subscriptions.disable(subscription);
        deliveries.complete(attempt,WebhookDeliveryStore.Result.httpError(503),3);
        assertThat(deliveries.get(delivery).status()).isEqualTo("CANCELED");assertThat(worker(3).processOne()).isFalse();assertThat(received).isEmpty();
    }

    @Test void redirectsAreNotFollowedAndRemovedTargetsFailWithoutNetwork() {
        mode.set("REDIRECT");worker(3).processOne();assertThat(otherRequests.get()).isZero();
        assertThat(deliveries.get(delivery).lastHttpStatus()).isEqualTo(302);due();
        try(var restricted=new WebhookHttp(new WebhookTargets(""),250)) {new WebhookWorker(deliveries,restricted,3,2000).processOne();}
        assertThat(deliveries.get(delivery).status()).isEqualTo("FAILED");assertThat(deliveries.get(delivery).lastError()).isEqualTo("URL_NOT_ALLOWED");assertThat(received).hasSize(1);
    }

    @Test void responseBodyCannotHoldAnAttemptPastItsTotalDeadline() {
        mode.set("SLOW");worker(3).processOne();
        assertThat(deliveries.get(delivery).status()).isEqualTo("PENDING");assertThat(deliveries.get(delivery).lastError()).isEqualTo("NETWORK_TIMEOUT");
    }

    @Test void failedCompletionSerializesWithDisableAndCannotRequeue() throws Exception {
        var attempt=deliveries.claim(3,2000).attempt();var disabled=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var disabling=pool.submit(()->tx.executeWithoutResult(s->{subscriptions.disable(subscription);disabled.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            assertThat(disabled.await(3,TimeUnit.SECONDS)).isTrue();
            var finishing=pool.submit(()->deliveries.complete(attempt,WebhookDeliveryStore.Result.httpError(503),3));
            try {assertThatThrownBy(()->finishing.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            disabling.get(5,TimeUnit.SECONDS);finishing.get(5,TimeUnit.SECONDS);
            assertThat(deliveries.get(delivery).status()).isEqualTo("CANCELED");
        }
    }
}
