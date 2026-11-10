package dev.payments.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="payments.worker.enabled=false")
class AccountApiTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static Server server;
    static final AtomicBoolean unavailable=new AtomicBoolean(),dropFundingResponse=new AtomicBoolean();
    @DynamicPropertySource static void setup(DynamicPropertyRegistry r) throws Exception {
        String url=DB.getJdbcUrl();
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=ledger_api",DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).schemas("ledger_api").locations("classpath:db/ledger").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        var ledger=new LedgerStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        var rpc=new LedgerRpc(ledger,new AccountStore(jdbc)) {
            @Override public void fundAccount(FundingRequest request,StreamObserver<FundingResult> response) {
                if(dropFundingResponse.getAndSet(false)) {
                    ledger.fund(request);
                    response.onError(Status.UNAVAILABLE.withDescription("injected lost response").asRuntimeException());
                } else super.fundAccount(request,response);
            }
        };
        server=NettyServerBuilder.forPort(0).addService(rpc).intercept(new ServerInterceptor() {
            @Override public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next) {
                if(unavailable.get()) {call.close(Status.UNAVAILABLE,new Metadata());return new ServerCall.Listener<>() {};}
                return next.startCall(call,headers);
            }
        }).build().start();
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);r.add("ledger.port",server::getPort);
    }
    @AfterAll static void stop() throws Exception {if(server!=null){server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);}}
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate paymentJdbc;
    @Autowired TransactionTemplate paymentTx;
    @Autowired LedgerClient client;

    String account() {
        String id=UUID.randomUUID().toString();
        assertThat(post("/accounts","{\"accountId\":\""+id+"\",\"currency\":\"USD\"}",null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id;
    }
    String funding(String id,long amount) {return "{\"accountId\":\""+id+"\",\"amountMinor\":"+amount+",\"currency\":\"USD\"}";}

    @Test void createsReplaysAndRetrievesAccount() throws Exception {
        String id=account();
        var replay=post("/accounts","{\"accountId\":\""+id+"\",\"currency\":\"USD\"}",null);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(replay.getBody()).path("balanceMinor").asLong()).isZero();
        assertThat(http.getForEntity("/accounts/"+id,String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/accounts?accountId="+id,String.class).getBody()).isEqualTo(replay.getBody());
        assertThat(http.getForEntity("/accounts/"+UUID.randomUUID(),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void fundsIdempotentlyAndRejectsChangedBody() throws Exception {
        String id=account(),key=UUID.randomUUID().toString();String body=funding(id,2500);
        var first=post("/funding",body,key);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var replay=post("/funding",body,key);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(replay.getBody())).isEqualTo(json.readTree(first.getBody()));
        assertThat(post("/funding",funding(id,2501),key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json.readTree(http.getForObject("/accounts/"+id,String.class)).path("balanceMinor").asLong()).isEqualTo(2500);
    }

    @Test void invalidAccountAndFundingInputReturns400() {
        for(String body:List.of("{}","null","{\"accountId\":\"__simulation_issuer__\",\"currency\":\"USD\"}",
                "{\"accountId\":\"x\",\"currency\":\"EUR\"}","{\"accountId\":\" \",\"currency\":\"USD\"}"))
            assertThat(post("/accounts",body,null).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        String id=account();
        for(String body:List.of("{}",funding(id,0),funding(id,-1),funding(id,1000000000001L),funding(id,100).replace("100","null"),funding("__simulation_issuer__",100)))
            assertThat(post("/funding",body,UUID.randomUUID().toString()).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/funding",funding(id,100),null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/funding",funding(id,100),"x".repeat(201)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void unknownFundingAccountStaysRejectedAfterCreation() {
        String id=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        assertThat(post("/funding",funding(id,100),key).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        post("/accounts","{\"accountId\":\""+id+"\",\"currency\":\"USD\"}",null);
        assertThat(post("/funding",funding(id,100),key).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void ledgerOutageReturns503AndLostFundingResponseIsSafeToReplay() throws Exception {
        String id=account();
        unavailable.set(true);
        try {assertThat(http.getForEntity("/accounts/"+id,String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);}
        finally {unavailable.set(false);}
        String key=UUID.randomUUID().toString();dropFundingResponse.set(true);
        assertThat(post("/funding",funding(id,1000),key).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(post("/funding",funding(id,1000),key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(http.getForObject("/accounts/"+id,String.class)).path("balanceMinor").asLong()).isEqualTo(1000);
    }

    @Test void invalidCustomerIdsAreRejectedBeforeCallingUnavailableLedger() {
        unavailable.set(true);
        try {
            assertThat(post("/accounts","{\"accountId\":\"__simulation_issuer__\",\"currency\":\"USD\"}",null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(post("/funding",funding("__simulation_issuer__",100),"invalid-funding").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.getForEntity("/accounts/__simulation_issuer__",String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {unavailable.set(false);}
    }

    @Test void rejectedPaymentIsRetrievedAndReplayedWithoutSpendingLaterFunding() throws Exception {
        String from=account(),to=account(),key=UUID.randomUUID().toString();
        String body="{\"sourceAccount\":\""+from+"\",\"destinationAccount\":\""+to+"\",\"amountMinor\":500,\"currency\":\"USD\"}";
        var first=post("/payments",body,key);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var path="/payments/"+json.readTree(first.getBody()).path("id").asText();
        var worker=new PaymentWorker(paymentJdbc,paymentTx,client);worker.processOne();
        var rejected=json.readTree(http.getForObject(path,String.class));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejectionReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.path("rejectedAt").isTextual()).isTrue();
        assertThat(post("/funding",funding(from,500),UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var replay=post("/payments",body,key);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(replay.getBody())).isEqualTo(rejected);
        assertThat(worker.processOne()).isFalse();
        assertThat(json.readTree(http.getForObject("/accounts/"+from,String.class)).path("balanceMinor").asLong()).isEqualTo(500);
        assertThat(post("/payments",body,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        worker.processOne();
        assertThat(json.readTree(http.getForObject("/accounts/"+from,String.class)).path("balanceMinor").asLong()).isZero();
        assertThat(json.readTree(http.getForObject("/accounts/"+to,String.class)).path("balanceMinor").asLong()).isEqualTo(500);
    }

    ResponseEntity<String> post(String path,String body,String key) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(key!=null)headers.set("Idempotency-Key",key);
        return http.postForEntity(path,new HttpEntity<>(body,headers),String.class);
    }
}
