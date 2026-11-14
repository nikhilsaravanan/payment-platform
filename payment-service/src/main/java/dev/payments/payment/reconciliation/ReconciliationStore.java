package dev.payments.payment.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static dev.payments.payment.reconciliation.ReconciliationRequest.*;
import static dev.payments.payment.reconciliation.ReconciliationEngine.*;

@Repository
public class ReconciliationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ReconciliationStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){this.jdbc=jdbc;this.tx=tx;this.json=json;}
    public record Report(UUID id,String accountId,Instant from,Instant to,Instant capturedAt,int processorCount,
                         int internalCount,int matchedCount,int findingCount,Instant createdAt) {}
    public record Created(Report report,boolean inserted) {}
    public record Evidence(ReconciliationRequest request,Instant capturedAt,List<Internal> internalSnapshot) {}
    public record Page(List<Finding> items,Integer nextAfter) {}
    private record Snapshot(Instant capturedAt,List<Internal> resources) {}
    private static final RowMapper<Report> REPORT=(rs,n)->new Report(rs.getObject("id",UUID.class),rs.getString("account_id"),
        rs.getTimestamp("window_from").toInstant(),rs.getTimestamp("window_to").toInstant(),rs.getTimestamp("captured_at").toInstant(),
        rs.getInt("processor_count"),rs.getInt("internal_count"),rs.getInt("matched_count"),rs.getInt("finding_count"),rs.getTimestamp("created_at").toInstant());

    public Created create(String key,ReconciliationRequest request) {
        return tx.execute(s->{
            // Transaction locks release automatically on rollback/crash. Hash collisions
            // only serialize unrelated keys; the unique key and request comparison remain exact.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"reconciliation:"+key);
            String encoded=encode(request);
            var existing=jdbc.query("SELECT * FROM reconciliation_reports WHERE idempotency_key=?",REPORT,key);
            if(!existing.isEmpty()) {
                boolean same=Boolean.TRUE.equals(jdbc.queryForObject("SELECT request=CAST(? AS jsonb) FROM reconciliation_reports WHERE idempotency_key=?",Boolean.class,encoded,key));
                if(!same)throw new Conflict();return new Created(existing.getFirst(),false);
            }
            validateWindow(request);
            var snapshot=capture(request);var comparison=ReconciliationEngine.compare(request,snapshot.resources());
            if(comparison.internalCount()>1000)throw new IllegalArgumentException("Scope exceeds 1000 resources; narrow the window");
            var id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO reconciliation_reports(id,idempotency_key,account_id,window_from,window_to,captured_at,
                    processor_count,internal_count,matched_count,finding_count,request,internal_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """,id,key,request.accountId(),Timestamp.from(request.from()),Timestamp.from(request.to()),Timestamp.from(snapshot.capturedAt()),
                request.records().size(),comparison.internalCount(),comparison.matchedCount(),comparison.findings().size(),encoded,encode(snapshot.resources()));
            jdbc.batchUpdate("""
                INSERT INTO reconciliation_findings(report_id,ordinal,code,operation_type,operation_id,processor_row,expected,actual)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """,comparison.findings(),100,(ps,f)->{
                    ps.setObject(1,id);ps.setInt(2,f.ordinal());ps.setString(3,f.code());ps.setString(4,f.operationType().name());ps.setObject(5,f.operationId());
                    ps.setObject(6,f.processorRow());ps.setString(7,f.expected()==null?null:encode(f.expected()));ps.setString(8,f.actual()==null?null:encode(f.actual()));
                });
            return new Created(get(id),true);
        });
    }
    private static void validateWindow(ReconciliationRequest request) {
        if(request.from()==null || request.to()==null || !request.from().isBefore(request.to())
            || Duration.between(request.from(),request.to()).compareTo(Duration.ofDays(31))>0 || request.to().isAfter(Instant.now())
            || request.from().getNano()%1000!=0 || request.to().getNano()%1000!=0 || request.accountId().equals("__simulation_issuer__"))
            throw new IllegalArgumentException("Invalid reconciliation window");
    }
    private Snapshot capture(ReconciliationRequest request) {
        var paymentIds=request.records().stream().filter(r->r.operationType()==OperationType.PAYMENT).map(ProcessorRecord::operationId).distinct().toList();
        var refundIds=request.records().stream().filter(r->r.operationType()==OperationType.REFUND).map(ProcessorRecord::operationId).distinct().toList();
        String payments=paymentIds.isEmpty()?"false":"p.id IN (:paymentIds)",refunds=refundIds.isEmpty()?"false":"r.id IN (:refundIds)";
        // A single statement snapshot across BOTH resource tables, including the
        // original merchant for refunds. RIGHT JOIN retains capture time for empty scopes.
        String sql="""
            SELECT resources.*,statement_timestamp() AS captured_at FROM (
                SELECT 'PAYMENT' AS operation_type,p.id,p.destination_account AS account_id,p.created_at,p.status,p.amount_minor,p.currency
                FROM payments p WHERE (p.destination_account=:account AND p.created_at>=:from AND p.created_at<:to) OR %s
                UNION ALL
                SELECT 'REFUND',r.id,p.destination_account,r.created_at,r.status,r.amount_minor,r.currency
                FROM refunds r JOIN payments p ON p.id=r.payment_id
                WHERE (p.destination_account=:account AND r.created_at>=:from AND r.created_at<:to) OR %s
            ) resources RIGHT JOIN (SELECT 1) anchor ON true LIMIT 2001
            """.formatted(payments,refunds);
        var parameters=new HashMap<String,Object>();parameters.put("account",request.accountId());parameters.put("from",Timestamp.from(request.from()));
        parameters.put("to",Timestamp.from(request.to()));parameters.put("paymentIds",paymentIds);parameters.put("refundIds",refundIds);
        return new NamedParameterJdbcTemplate(jdbc).query(sql,parameters,rs->{
            Instant captured=null;var rows=new ArrayList<Internal>();
            while(rs.next()) {
                captured=rs.getTimestamp("captured_at").toInstant();
                if(rs.getObject("id")!=null)rows.add(new Internal(OperationType.valueOf(rs.getString("operation_type")),rs.getObject("id",UUID.class),
                    rs.getString("account_id"),rs.getTimestamp("created_at").toInstant(),rs.getString("status"),rs.getLong("amount_minor"),rs.getString("currency")));
            }
            if(rows.size()>2000)throw new IllegalArgumentException("Scope exceeds 1000 resources; narrow the window");
            rows.sort(Comparator.comparing((Internal r)->r.operationType().name()).thenComparing(r->r.operationId().toString()));
            return new Snapshot(captured,List.copyOf(rows));
        });
    }
    public Report get(UUID id) {
        var reports=jdbc.query("SELECT * FROM reconciliation_reports WHERE id=?",REPORT,id);
        if(reports.isEmpty())throw new NotFound();return reports.getFirst();
    }
    public Evidence evidence(UUID id) {
        var report=get(id);
        return jdbc.queryForObject("SELECT request,internal_snapshot FROM reconciliation_reports WHERE id=?",(rs,n)->
            new Evidence(decode(rs.getString("request"),ReconciliationRequest.class),report.capturedAt(),decode(rs.getString("internal_snapshot"),new TypeReference<List<Internal>>(){})),id);
    }
    public Page findings(UUID id,int limit,int after) {
        if(limit<1 || limit>100 || after<0)throw new IllegalArgumentException("Invalid findings page");get(id);
        var rows=jdbc.query("SELECT * FROM reconciliation_findings WHERE report_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",(rs,n)->
            new Finding(rs.getInt("ordinal"),rs.getString("code"),OperationType.valueOf(rs.getString("operation_type")),rs.getObject("operation_id",UUID.class),
                rs.getObject("processor_row",Integer.class),decode(rs.getString("expected"),Internal.class),decode(rs.getString("actual"),ProcessorRecord.class)),id,after,limit+1);
        boolean more=rows.size()>limit;var items=List.copyOf(more?rows.subList(0,limit):rows);
        return new Page(items,more?items.getLast().ordinal():null);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode reconciliation evidence",e);}}
    private <T>T decode(String value,Class<T> type){if(value==null)return null;try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot decode reconciliation evidence",e);}}
    private <T>T decode(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot decode reconciliation evidence",e);}}
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
}
