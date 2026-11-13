package dev.payments.payment.webhooks;

import dev.payments.payment.*;
import dev.payments.contract.*;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class WebhookSchedulingTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void blockedCallbackDoesNotDelayPaymentsOrRefunds() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var receiver=HttpServer.create(new InetSocketAddress("localhost",0),0);
        receiver.createContext("/hooks",exchange->{
            entered.countDown();
            try {release.await(20,TimeUnit.SECONDS);exchange.sendResponseHeaders(204,-1);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        receiver.start();
        // Stub only the healthy ledger boundary: this regression targets scheduler isolation.
        Server ledger=NettyServerBuilder.forPort(0).addService(new LedgerGrpc.LedgerImplBase(){
            @Override public void submitTransfer(Transfer r,StreamObserver<TransferResult> o){
                o.onNext(TransferResult.newBuilder().setPaymentId(r.getPaymentId()).setOutcome(TransferOutcome.POSTED).build());o.onCompleted();
            }
            @Override public void submitRefund(RefundTransfer r,StreamObserver<RefundResult> o){
                o.onNext(RefundResult.newBuilder().setRefundId(r.getRefundId()).setPaymentId(r.getPaymentId())
                    .setAmountMinor(r.getAmountMinor()).setCurrency(r.getCurrency()).setOutcome(RefundOutcome.REFUND_POSTED).build());o.onCompleted();
            }
        }).build().start();
        String url="http://localhost:"+receiver.getAddress().getPort()+"/hooks";
        try(var context=new SpringApplicationBuilder(PaymentApplication.class).run(
            "--server.port=0","--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),
            "--spring.datasource.password="+DB.getPassword(),"--ledger.port="+ledger.getPort(),
            "--payments.worker.poll-millis=20","--webhooks.poll-millis=20","--webhooks.timeout-millis=10000",
            "--webhooks.lease-millis=20000","--webhooks.allowed-urls="+url)) {
            try {
                var payments=context.getBean(PaymentStore.class);var refunds=context.getBean(RefundStore.class);
                context.getBean(WebhookSubscriptions.class).create("scheduler-sub","merchant",url,"s".repeat(40));
                payments.create("callback-payment",new CreatePayment("payer","merchant",100L,"USD"));
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var p=payments.create("independent-payment",new CreatePayment("payer","other",100L,"USD")).payment();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(payments.get(p.id()).status()).isEqualTo("COMPLETED"));
                var r=refunds.create(p.id(),"independent-refund",50).refund();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(refunds.get(r.id()).status()).isEqualTo("COMPLETED"));
                assertThat(release.getCount()).isEqualTo(1);
            } finally {release.countDown();}
        } finally {release.countDown();receiver.stop(0);ledger.shutdownNow();ledger.awaitTermination(5,TimeUnit.SECONDS);}
    }
}
