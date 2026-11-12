package dev.payments.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="payments.worker.enabled=false")
class PaymentApiTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    static final String BODY="{\"sourceAccount\":\"alice\",\"destinationAccount\":\"merchant\",\"amountMinor\":2500,\"currency\":\"USD\"}";

    @Test void createsRetrievesReplaysAndRejectsChangedBody() throws Exception {
        var key=UUID.randomUUID().toString();
        var first=post(key,BODY);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var resource=json.readTree(first.getBody());
        assertThat(resource.path("status").asText()).isEqualTo("PENDING");
        assertThat(resource.path("amountMinor").asLong()).isEqualTo(2500);
        assertThat(first.getHeaders().getLocation().toString()).isEqualTo("/payments/"+resource.path("id").asText());
        var get=http.getForEntity(first.getHeaders().getLocation(),String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(get.getBody())).isEqualTo(resource);
        var replay=post(key,BODY);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(replay.getBody())).isEqualTo(resource);
        assertThat(post(key,BODY.replace("2500","2501")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity("/payments/"+UUID.randomUUID(),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/payments/bad-id",String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void invalidRequestsNeverCreatePayments() {
        long before=jdbc.queryForObject("SELECT count(*) FROM payments",Long.class);
        var badBodies=List.of("{}","null","{", BODY.replace("2500","null"), BODY.replace("2500","0"),
                BODY.replace("2500","-1"),BODY.replace("2500","1000000000001"),
                BODY.replace("2500","9223372036854775808"),BODY.replace("2500","2.5"),BODY.replace("2500","\"2500\""),
                BODY.replace("\"alice\"","null"),BODY.replace("alice"," "),
                BODY.replace("merchant","alice"),BODY.replace("USD","EUR"),BODY.replace("alice","a".repeat(101)),
                BODY.replace("alice","__simulation_issuer__"));
        for(var body:badBodies) assertThat(post(UUID.randomUUID().toString(),body).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        for(var key:Arrays.asList(null,"","a".repeat(201))) assertThat(post(key,BODY).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(before);
    }

    @Test void concurrentMatchingRequestsHaveOnePayment() throws Exception {
        String key=UUID.randomUUID().toString();
        var results=concurrent(key,false);
        assertThat(results.stream().filter(r->r.getStatusCode()==HttpStatus.ACCEPTED).count()).isEqualTo(1);
        var ids=new HashSet<String>();
        for(var r:results) {
            assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
            ids.add(json.readTree(r.getBody()).path("id").asText());
        }
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE idempotency_key=?",Long.class,key)).isEqualTo(1);
    }

    @Test void concurrentDifferentBodiesHaveOneWinner() throws Exception {
        String key=UUID.randomUUID().toString();
        var results=concurrent(key,true);
        assertThat(results.stream().filter(r->r.getStatusCode()==HttpStatus.ACCEPTED).count()).isEqualTo(1);
        assertThat(results.stream().filter(r->r.getStatusCode()==HttpStatus.CONFLICT).count()).isEqualTo(12);
        for(var r:results) assertThat(r.getStatusCode()).isIn(HttpStatus.ACCEPTED,HttpStatus.OK,HttpStatus.CONFLICT);
        var accepted=results.stream().filter(r->r.getStatusCode()==HttpStatus.ACCEPTED).findFirst().orElseThrow();
        var winner=json.readTree(accepted.getBody());
        for(var r:results) if(r.getStatusCode()==HttpStatus.OK) assertThat(json.readTree(r.getBody())).isEqualTo(winner);
    }

    private List<ResponseEntity<String>> concurrent(String key,boolean different) throws Exception {
        try(var pool=Executors.newFixedThreadPool(24)) {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<ResponseEntity<String>>>();
            for(int i=0;i<24;i++) {
                String body=different && i%2==1 ? BODY.replace("2500","2501") : BODY;
                futures.add(pool.submit(()-> {start.await();return post(key,body);}));
            }
            start.countDown();
            var results=new ArrayList<ResponseEntity<String>>();
            for(var f:futures) results.add(f.get(30,TimeUnit.SECONDS));
            return results;
        }
    }

    private ResponseEntity<String> post(String key,String body) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(key!=null) headers.set("Idempotency-Key",key);
        return http.postForEntity("/payments",new HttpEntity<>(body,headers),String.class);
    }
}
