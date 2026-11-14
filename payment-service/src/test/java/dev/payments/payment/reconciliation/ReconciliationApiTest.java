package dev.payments.payment.reconciliation;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"payments.worker.enabled=false","webhooks.worker.enabled=false"})
class ReconciliationApiTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    static final String EMPTY="{\"accountId\":\"merchant\",\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\",\"records\":[]}";
    static final String ROW="{\"reference\":\"processor-1\",\"operationType\":\"PAYMENT\",\"operationId\":\"00000000-0000-0000-0000-000000000001\",\"amountMinor\":2500,\"currency\":\"USD\"}";
    ResponseEntity<String> post(String key,String body){var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(key!=null)headers.set("Idempotency-Key",key);return http.postForEntity("/reconciliation-reports",new HttpEntity<>(body,headers),String.class);}
    JsonNode body(ResponseEntity<String> response) throws Exception{return json.readTree(response.getBody());}
    String withRows(String rows){return EMPTY.replace("[]","["+rows+"]");}

    @Test void createsReplaysAndPreservesSemanticJsonIdentityWithoutLedger() throws Exception {
        var key=UUID.randomUUID().toString();var first=post(key,EMPTY);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var location=first.getHeaders().getLocation();assertThat(location).isNotNull();
        assertThat(body(http.getForEntity(location,String.class))).isEqualTo(body(first));
        String reordered="{\"records\":[],\"to\":\"2026-01-02T01:00:00+01:00\",\"from\":\"2026-01-01T00:00:00Z\",\"accountId\":\"merchant\"}";
        var replay=post(key,reordered);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(body(replay)).isEqualTo(body(first));
        assertThat(post(key,EMPTY.replace("merchant","other")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var evidence=body(http.getForEntity(location+"/evidence",String.class));assertThat(evidence.path("request").path("records").isArray()).isTrue();assertThat(evidence.path("internalSnapshot").isEmpty()).isTrue();
    }
    @Test void rejectsMalformedTypedRecordsWithoutPersistingPartialReports(){
        long before=jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Long.class);
        var invalid=new ArrayList<>(List.of("{}","null","{",EMPTY.replace("merchant"," "),EMPTY.replace("merchant","__simulation_issuer__"),
            EMPTY.replace("merchant","m".repeat(101)),EMPTY.replace("2026-01-02T00:00:00Z","2026-01-01T00:00:00Z"),
            EMPTY.replace("2026-01-02T00:00:00Z","2026-02-02T00:00:00Z"),EMPTY.replace("2026-01-02T00:00:00Z","2099-01-02T00:00:00Z"),
            EMPTY.replace("2026-01-01T00:00:00Z","2026-01-01T00:00:00.000000001Z"),EMPTY.replace("[]","null"),EMPTY.replace("[]","[null]"),EMPTY.replace("\"records\"","\"unknown\"")));
        for(String row:List.of(ROW.replace("2500","0"),ROW.replace("2500","-1"),ROW.replace("2500","1000000000001"),ROW.replace("2500","2.5"),ROW.replace("2500","\"2500\""),
            ROW.replace("2500","null"),ROW.replace("USD","usd"),ROW.replace("USD","US"),ROW.replace("PAYMENT","FUNDING"),ROW.replace("PAYMENT","payment"),
            ROW.replace("processor-1"," "),ROW.replace("processor-1","r".repeat(101)),ROW.replace("00000000-0000-0000-0000-000000000001","bad-uuid")))invalid.add(withRows(row));
        invalid.add(withRows(String.join(",",Collections.nCopies(1001,ROW))));
        for(String value:invalid)assertThat(post(UUID.randomUUID().toString(),value).getStatusCode()).as(value.substring(0,Math.min(value.length(),160))).isEqualTo(HttpStatus.BAD_REQUEST);
        for(String key:Arrays.asList(null,""," ","k".repeat(201)))assertThat(post(key,EMPTY).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_reports",Long.class)).isEqualTo(before);
    }
    @Test void typedProcessorClaimAndEvidenceExposePendingAndCurrencyMismatch() throws Exception {
        var id=UUID.randomUUID();String merchant="merchant-"+id;
        jdbc.update("INSERT INTO payments(id,idempotency_key,source_account,destination_account,amount_minor,currency,created_at) VALUES (?,?, 'payer',?,2500,'USD','2026-01-01T00:00:00Z')",id,id.toString(),merchant);
        String row=ROW.replace("00000000-0000-0000-0000-000000000001",id.toString()).replace("USD","EUR");
        var response=post(UUID.randomUUID().toString(),withRows(row).replace("\"merchant\"","\""+merchant+"\""));var path=response.getHeaders().getLocation();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(body(response).path("internalCount").asInt()).isEqualTo(1);
        var findings=body(http.getForEntity(path+"/findings",String.class)).path("items");
        assertThat(findings.findValuesAsText("code")).containsExactly("INTERNAL_NOT_COMPLETED","CURRENCY_MISMATCH");
        assertThat(findings.get(0).path("expected").path("status").asText()).isEqualTo("PENDING");
        assertThat(findings.get(1).path("expected").path("currency").asText()).isEqualTo("USD");
        assertThat(findings.get(1).path("actual").path("currency").asText()).isEqualTo("EUR");
    }
    @Test void concurrentHttpImportsReturnOneCreatedAndOneReportId() throws Exception {
        String key=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(12)){
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<ResponseEntity<String>>>();
            for(int i=0;i<24;i++)jobs.add(pool.submit(()->{start.await();return post(key,EMPTY);}));start.countDown();int created=0;var ids=new HashSet<String>();
            for(var job:jobs){var result=job.get(20,TimeUnit.SECONDS);assertThat(result.getStatusCode()).isIn(HttpStatus.CREATED,HttpStatus.OK);if(result.getStatusCode()==HttpStatus.CREATED)created++;ids.add(body(result).path("id").asText());}
            assertThat(created).isEqualTo(1);assertThat(ids).hasSize(1);
        }
    }
    @Test void pagesFindingsWithoutSkipsAndRejectsInvalidPaginationAndMissingResources() throws Exception {
        var response=post(UUID.randomUUID().toString(),withRows(ROW+","+ROW));String path=response.getHeaders().getLocation().toString();
        var ordinals=new ArrayList<Integer>();int after=0;
        do {var page=body(http.getForEntity(path+"/findings?limit=1&after="+after,String.class));for(var finding:page.path("items"))ordinals.add(finding.path("ordinal").asInt());
            if(page.path("nextAfter").isNull())break;after=page.path("nextAfter").asInt();}while(after<10);
        assertThat(ordinals).containsExactly(1,2,3,4);
        for(String query:List.of("limit=0","limit=101","after=-1","after=abc"))assertThat(http.getForEntity(path+"/findings?"+query,String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for(String suffix:List.of("","/evidence","/findings"))assertThat(http.getForEntity("/reconciliation-reports/"+UUID.randomUUID()+suffix,String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
