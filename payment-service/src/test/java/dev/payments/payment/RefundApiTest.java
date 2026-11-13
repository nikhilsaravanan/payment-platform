package dev.payments.payment;

import com.fasterxml.jackson.databind.*;
import dev.payments.contract.*;
import dev.payments.ledger.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"payments.worker.enabled=false","webhooks.worker.enabled=false","ledger.deadline-millis=10000"})
class RefundApiTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static Server server;
    static JdbcTemplate ledgerJdbc;
    static final AtomicBoolean unavailable=new AtomicBoolean(),dropResponse=new AtomicBoolean();
    static final AtomicReference<String> malformed=new AtomicReference<>();
    static final AtomicReference<Gate> gate=new AtomicReference<>();
    record Gate(CountDownLatch entered,CountDownLatch release) {}

    @DynamicPropertySource static void setup(DynamicPropertyRegistry r) throws Exception {
        String url=DB.getJdbcUrl();
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=refund_api",DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).schemas("refund_api").locations("classpath:db/ledger").load().migrate();
        ledgerJdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var ledger=new LedgerStore(ledgerJdbc,tx);var refunds=new LedgerRefundStore(ledgerJdbc,tx,ledger);
        var rpc=new LedgerRpc(ledger,new AccountStore(ledgerJdbc),refunds,new LedgerHistoryStore(ledgerJdbc)) {
            @Override public void submitRefund(RefundTransfer request,StreamObserver<RefundResult> response) {
                String bad=malformed.getAndSet(null);
                if(bad!=null) {
                    response.onNext(RefundResult.newBuilder().setRefundId(request.getRefundId()).setPaymentId(request.getPaymentId())
                        .setAmountMinor(bad.equals("amount")?request.getAmountMinor()+1:request.getAmountMinor()).setCurrency("USD")
                        .setOutcome(bad.equals("unknown")?RefundOutcome.REFUND_OUTCOME_UNSPECIFIED:RefundOutcome.REFUND_POSTED).build());
                    response.onCompleted();return;
                }
                var held=gate.get();
                if(held!=null) {
                    held.entered().countDown();
                    try {if(!held.release().await(8,TimeUnit.SECONDS)) throw new IllegalStateException("coordination timeout");}
                    catch(Exception e){response.onError(Status.INTERNAL.asRuntimeException());return;}
                }
                if(dropResponse.getAndSet(false)) {
                    refunds.submit(request);response.onError(Status.UNAVAILABLE.withDescription("response lost after decision").asRuntimeException());
                } else super.submitRefund(request,response);
            }
        };
        server=NettyServerBuilder.forPort(0).addService(rpc).intercept(new ServerInterceptor() {
            @Override public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next) {
                if(unavailable.get()){call.close(Status.UNAVAILABLE,new Metadata());return new ServerCall.Listener<>() {};}
                return next.startCall(call,headers);
            }
        }).build().start();
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("ledger.port",server::getPort);
    }
    @AfterAll static void stop() throws Exception {if(server!=null){server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);}}
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired LedgerClient client;

    record Original(String id,String source,String merchant,long amount) {}
    RefundWorker worker(){return new RefundWorker(jdbc,tx,client);}
    String account() throws Exception {
        String id=UUID.randomUUID().toString();assertThat(post("/accounts","{\"accountId\":\""+id+"\",\"currency\":\"USD\"}",null).getStatusCode()).isEqualTo(HttpStatus.CREATED);return id;
    }
    void fund(String id,long amount) {assertThat(post("/funding","{\"accountId\":\""+id+"\",\"amountMinor\":"+amount+",\"currency\":\"USD\"}",UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);}
    Original payment(long amount,boolean complete) throws Exception {
        String source=account(),merchant=account();fund(source,amount);
        var response=post("/payments",paymentBody(source,merchant,amount),UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id=json.readTree(response.getBody()).path("id").asText();
        if(complete)new PaymentWorker(jdbc,tx,client).processOne();
        return new Original(id,source,merchant,amount);
    }
    String paymentBody(String from,String to,long amount) {return "{\"sourceAccount\":\""+from+"\",\"destinationAccount\":\""+to+"\",\"amountMinor\":"+amount+",\"currency\":\"USD\"}";}
    ResponseEntity<String> refund(Original p,long amount,String key) {return post("/payments/"+p.id()+"/refunds","{\"amountMinor\":"+amount+"}",key);}
    String accepted(Original p,long amount) throws Exception {
        var r=refund(p,amount,UUID.randomUUID().toString());assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id=json.readTree(r.getBody()).path("id").asText();assertThat(r.getHeaders().getLocation().toString()).isEqualTo("/refunds/"+id);return id;
    }
    JsonNode get(String id) throws Exception {return json.readTree(http.getForObject("/refunds/"+id,String.class));}
    long balance(String id) throws Exception {return json.readTree(http.getForObject("/accounts/"+id,String.class)).path("balanceMinor").asLong();}
    void due(String id) {jdbc.update("UPDATE refunds SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?::uuid",id);}

    @Test void partialFullRefundsReplayAndEnforceRemainingAmount() throws Exception {
        var p=payment(1000,true);String key=UUID.randomUUID().toString();
        var first=refund(p,300,key);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id=json.readTree(first.getBody()).path("id").asText();worker().processOne();
        var completed=get(id);assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("completedAt").isTextual()).isTrue();
        assertThat(json.readTree(refund(p,300,key).getBody())).isEqualTo(completed);
        assertThat(refund(p,301,key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String rest=accepted(p,700);worker().processOne();assertThat(get(rest).path("status").asText()).isEqualTo("COMPLETED");
        String excess=accepted(p,1);worker().processOne();assertThat(get(excess).path("rejectionReason").asText()).isEqualTo("REFUND_LIMIT_EXCEEDED");
        assertThat(balance(p.source())).isEqualTo(1000);assertThat(balance(p.merchant())).isZero();
        assertThat(json.readTree(http.getForObject("/payments/"+p.id(),String.class)).path("status").asText()).isEqualTo("COMPLETED");
        var full=payment(500,true);String fullId=accepted(full,500);worker().processOne();assertThat(get(fullId).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test void concurrentMatchingRefundRequestsHaveOneResourceAndOneEffect() throws Exception {
        var p=payment(1000,true);String key=UUID.randomUUID().toString();var responses=new ArrayList<ResponseEntity<String>>();
        try(var pool=Executors.newFixedThreadPool(12)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<ResponseEntity<String>>>();
            for(int i=0;i<24;i++)futures.add(pool.submit(()->{start.await();return refund(p,100,key);}));
            start.countDown();for(var f:futures)responses.add(f.get(20,TimeUnit.SECONDS));
        }
        assertThat(responses.stream().filter(r->r.getStatusCode()==HttpStatus.ACCEPTED).count()).isEqualTo(1);
        var ids=new HashSet<String>();for(var r:responses){assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();ids.add(json.readTree(r.getBody()).path("id").asText());}
        assertThat(ids).hasSize(1);worker().processOne();assertThat(balance(p.source())).isEqualTo(100);
        var other=payment(1000,true);assertThat(refund(other,100,key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void invalidRequestsAndIneligiblePaymentsDoNotQueueRefunds() throws Exception {
        var p=payment(1000,false);
        assertThat(refund(p,100,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        new PaymentWorker(jdbc,tx,client).processOne();
        long before=jdbc.queryForObject("SELECT count(*) FROM refunds",Long.class);
        for(String body:List.of("{}","null","{\"amountMinor\":null}","{\"amountMinor\":0}","{\"amountMinor\":-1}","{\"amountMinor\":1000000000001}","{\"amountMinor\":1.5}","{\"amountMinor\":\"100\"}"))
            assertThat(post("/payments/"+p.id()+"/refunds",body,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refund(p,100,null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refund(p,100,"x".repeat(201)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/payments/"+UUID.randomUUID()+"/refunds","{\"amountMinor\":100}","unknown").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/refunds/"+UUID.randomUUID(),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/refunds/not-a-uuid",String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds",Long.class)).isEqualTo(before);
    }

    @Test void ledgerOutageLeavesDurableRefundForReplacementWorker() throws Exception {
        var p=payment(1000,true);String id=accepted(p,100);unavailable.set(true);
        try {worker().processOne();assertThat(get(id).path("status").asText()).isEqualTo("PENDING");}
        finally {unavailable.set(false);}
        due(id);worker().processOne();assertThat(get(id).path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(p.source())).isEqualTo(100);
    }

    @Test void lostSuccessResponseRecoversOneReversal() throws Exception {
        var p=payment(1000,true);String id=accepted(p,100);dropResponse.set(true);worker().processOne();
        assertThat(get(id).path("status").asText()).isEqualTo("PENDING");assertThat(balance(p.source())).isEqualTo(100);
        due(id);worker().processOne();assertThat(get(id).path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(p.source())).isEqualTo(100);
        assertThat(ledgerJdbc.queryForObject("SELECT count(*) FROM entries WHERE payment_id=?::uuid",Long.class,id)).isEqualTo(2);
    }

    @Test void localCompletionFailureRollsBackThenReplaysLedgerRefund() throws Exception {
        var p=payment(1000,true);String id=accepted(p,100);
        jdbc.execute("CREATE FUNCTION fail_refund_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected local refund failure'; END $$");
        jdbc.execute("CREATE TRIGGER injected_refund_completion BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION fail_refund_completion()");
        try {assertThatThrownBy(()->worker().processOne()).isInstanceOf(RuntimeException.class);assertThat(get(id).path("status").asText()).isEqualTo("PENDING");}
        finally {jdbc.execute("DROP TRIGGER injected_refund_completion ON refunds");jdbc.execute("DROP FUNCTION fail_refund_completion()");}
        worker().processOne();assertThat(get(id).path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(p.source())).isEqualTo(100);
    }

    @Test void lostRejectionRemainsRejectedAfterMerchantFunding() throws Exception {
        var p=payment(1000,true);String sink=account();
        post("/payments",paymentBody(p.merchant(),sink,1000),UUID.randomUUID().toString());new PaymentWorker(jdbc,tx,client).processOne();
        String key=UUID.randomUUID().toString();var r=refund(p,200,key);String id=json.readTree(r.getBody()).path("id").asText();
        dropResponse.set(true);worker().processOne();assertThat(get(id).path("status").asText()).isEqualTo("PENDING");
        fund(p.merchant(),500);due(id);worker().processOne();
        var rejected=get(id);assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejectionReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");assertThat(rejected.path("rejectedAt").isTextual()).isTrue();
        assertThat(json.readTree(refund(p,200,key).getBody())).isEqualTo(rejected);
        assertThat(balance(p.source())).isZero();assertThat(balance(p.merchant())).isEqualTo(500);
    }

    @Test void competingRefundWorkersSkipLockedRows() throws Exception {
        var p=payment(1000,true);String id=accepted(p,100);var held=new Gate(new CountDownLatch(1),new CountDownLatch(1));gate.set(held);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->worker().processOne());
            try {assertThat(held.entered().await(3,TimeUnit.SECONDS)).isTrue();assertThat(pool.submit(()->worker().processOne()).get(3,TimeUnit.SECONDS)).isFalse();}
            finally {held.release().countDown();gate.set(null);}
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(get(id).path("status").asText()).isEqualTo("COMPLETED");assertThat(balance(p.source())).isEqualTo(100);
    }

    @Test void malformedRefundOutcomesRemainPendingForInvestigation() throws Exception {
        var p=payment(1000,true);
        for(String mode:List.of("unknown","amount")) {
            String id=accepted(p,100);malformed.set(mode);worker().processOne();
            assertThat(get(id).path("status").asText()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT last_error FROM refunds WHERE id=?::uuid",String.class,id)).isEqualTo("DATA_LOSS");
            jdbc.update("UPDATE refunds SET next_attempt_at=clock_timestamp()+interval '1 day' WHERE id=?::uuid",id);
        }
    }

    @Test void historyPaginatesRefundPaymentAndFundingAndValidatesInput() throws Exception {
        var p=payment(1000,true);String refundId=accepted(p,250);worker().processOne();
        String path="/accounts/"+p.source()+"/transactions";
        var first=http.getForEntity(path+"?limit=1",String.class);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        var page=json.readTree(first.getBody());assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("type").asText()).isEqualTo("REFUND");
        assertThat(page.path("items").get(0).path("transactionId").asText()).isEqualTo(refundId);
        assertThat(page.path("items").get(0).path("originalPaymentId").asText()).isEqualTo(p.id());
        var second=json.readTree(http.getForObject(path+"?limit=1&cursor="+page.path("nextCursor").asText(),String.class));
        assertThat(second.path("items").get(0).path("type").asText()).isEqualTo("PAYMENT");
        var third=json.readTree(http.getForObject(path+"?limit=1&cursor="+second.path("nextCursor").asText(),String.class));
        assertThat(third.path("items").get(0).path("type").asText()).isEqualTo("FUNDING");assertThat(third.path("nextCursor").isNull()).isTrue();
        assertThat(json.readTree(http.getForObject("/transactions?accountId="+p.source(),String.class)).path("items").size()).isEqualTo(3);
        for(String query:List.of("?limit=0","?limit=101","?cursor=bad-cursor"))
            assertThat(http.getForEntity(path+query,String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/accounts/"+UUID.randomUUID()+"/transactions",String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        unavailable.set(true);
        try {assertThat(http.getForEntity(path,String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);}
        finally {unavailable.set(false);}
    }

    ResponseEntity<String> post(String path,String body,String key) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(key!=null)headers.set("Idempotency-Key",key);
        return http.postForEntity(path,new HttpEntity<>(body,headers),String.class);
    }
}
