package dev.payments.payment.webhooks;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class WebhookDeliveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public WebhookDeliveryStore(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    public record Delivery(UUID id,UUID eventId,UUID subscriptionId,String eventType,UUID resourceId,String status,int attemptCount,
        Instant nextAttemptAt,Instant leaseUntil,Integer lastHttpStatus,String lastError,Instant createdAt,Instant finishedAt) {}
    public record AttemptView(int attemptNumber,String outcome,Instant startedAt,Instant finishedAt,Integer httpStatus,String error) {}
    private static Instant instant(ResultSet rs,String column) throws SQLException {var t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    private static final RowMapper<Delivery> MAPPER=(rs,n)->new Delivery(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),
        rs.getObject("subscription_id",UUID.class),rs.getString("event_type"),rs.getObject("resource_id",UUID.class),rs.getString("status"),rs.getInt("attempt_count"),
        instant(rs,"next_attempt_at"),instant(rs,"lease_until"),rs.getObject("last_http_status",Integer.class),rs.getString("last_error"),instant(rs,"created_at"),instant(rs,"finished_at"));
    public Delivery get(UUID id) {
        var rows=jdbc.query("SELECT d.*,e.event_type,e.resource_id FROM webhook_deliveries d JOIN webhook_events e ON e.id=d.event_id WHERE d.id=?",MAPPER,id);
        if(rows.isEmpty())throw new WebhookSubscriptions.NotFound();return rows.getFirst();
    }
    public List<Delivery> list(UUID subscription,int limit) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("Limit must be 1..100");
        return jdbc.query("SELECT d.*,e.event_type,e.resource_id FROM webhook_deliveries d JOIN webhook_events e ON e.id=d.event_id WHERE d.subscription_id=? ORDER BY d.created_at DESC,d.id DESC LIMIT ?",MAPPER,subscription,limit);
    }
    public List<AttemptView> attempts(UUID id) {
        get(id);return jdbc.query("SELECT * FROM webhook_attempts WHERE delivery_id=? ORDER BY attempt_number",(rs,n)->new AttemptView(rs.getInt("attempt_number"),
            rs.getString("outcome"),instant(rs,"started_at"),instant(rs,"finished_at"),rs.getObject("http_status",Integer.class),rs.getString("error")),id);
    }
    public String event(UUID id) {
        var rows=jdbc.queryForList("SELECT payload FROM webhook_events WHERE id=?",String.class,id);
        if(rows.isEmpty())throw new WebhookSubscriptions.NotFound();return rows.getFirst();
    }

    public record Attempt(UUID id,UUID token,int number,UUID eventId,UUID subscriptionId,String eventType,String url,String secret,String payload) {
        @Override public String toString(){return "WebhookAttempt[id="+id+", number="+number+"]";}
    }
    public record Claim(boolean progressed,Attempt attempt) {}
    public record Result(Integer status,String error,String auditOutcome,boolean delivered,boolean permanent) {
        public static Result success(int status){return new Result(status,null,"SUCCEEDED",true,false);}
        public static Result httpError(int status){return new Result(status,"HTTP_"+status,"HTTP_ERROR",false,false);}
        public static Result network(String error){return new Result(null,error,"NETWORK_ERROR",false,false);}
        public static Result policy(){return new Result(null,"URL_NOT_ALLOWED","POLICY_REJECTED",false,true);}
    }
    private record Candidate(UUID id,UUID eventId,UUID subscriptionId,int count,String state,boolean active,String eventType,String url,String secret,String payload) {}

    public Claim claim(int maxAttempts,long leaseMillis) {
        if(maxAttempts<1 || maxAttempts>20 || leaseMillis<1)throw new IllegalArgumentException("Invalid delivery budget");
        return tx.execute(s->{
            // Lock subscriptions before deliveries, matching disable and completion. Candidate
            // discovery is unlocked; every due predicate is checked again after taking locks.
            var candidates=jdbc.query("""
                SELECT id,subscription_id FROM webhook_deliveries
                WHERE (status='PENDING' AND next_attempt_at<=clock_timestamp())
                   OR (status='IN_FLIGHT' AND lease_until<=clock_timestamp())
                ORDER BY COALESCE(next_attempt_at,lease_until),created_at LIMIT 20
                """,(rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("subscription_id",UUID.class)});
            Candidate row=null;
            for(var candidate:candidates) {
                var active=jdbc.query("SELECT active FROM webhook_subscriptions WHERE id=? FOR SHARE SKIP LOCKED",
                    (rs,n)->rs.getBoolean("active"),candidate[1]);
                if(active.isEmpty())continue;
                var rows=jdbc.query("""
                    SELECT d.*,s.url,s.signing_secret,e.payload,e.event_type FROM webhook_deliveries d
                    JOIN webhook_subscriptions s ON s.id=d.subscription_id JOIN webhook_events e ON e.id=d.event_id
                    WHERE d.id=? AND ((d.status='PENDING' AND d.next_attempt_at<=clock_timestamp())
                        OR (d.status='IN_FLIGHT' AND d.lease_until<=clock_timestamp()))
                    FOR UPDATE OF d SKIP LOCKED
                    """,(rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),rs.getObject("subscription_id",UUID.class),
                        rs.getInt("attempt_count"),rs.getString("status"),active.getFirst(),rs.getString("event_type"),rs.getString("url"),rs.getString("signing_secret"),rs.getString("payload")),candidate[0]);
                if(!rows.isEmpty()){row=rows.getFirst();break;}
            }
            if(row==null)return new Claim(false,null);
            if(row.state().equals("IN_FLIGHT"))
                jdbc.update("UPDATE webhook_attempts SET outcome='ABANDONED',finished_at=clock_timestamp(),error='LEASE_EXPIRED' WHERE delivery_id=? AND attempt_number=? AND outcome='IN_FLIGHT'",row.id(),row.count());
            if(!row.active() || row.count()>=maxAttempts) {
                jdbc.update("""
                    UPDATE webhook_deliveries SET status=?,finished_at=clock_timestamp(),next_attempt_at=NULL,
                        lease_token=NULL,lease_until=NULL,last_error=? WHERE id=?
                    """,row.active()?"FAILED":"CANCELED",row.active()?"ATTEMPT_BUDGET_EXHAUSTED":"SUBSCRIPTION_DISABLED",row.id());
                return new Claim(true,null);
            }
            UUID token=UUID.randomUUID();int number=row.count()+1;
            jdbc.update("""
                UPDATE webhook_deliveries SET status='IN_FLIGHT',attempt_count=?,next_attempt_at=NULL,
                    lease_token=?,lease_until=clock_timestamp()+(?*interval '1 millisecond') WHERE id=?
                """,number,token,leaseMillis,row.id());
            jdbc.update("INSERT INTO webhook_attempts(delivery_id,attempt_number,lease_token,outcome) VALUES (?,?,?,'IN_FLIGHT')",row.id(),number,token);
            return new Claim(true,new Attempt(row.id(),token,number,row.eventId(),row.subscriptionId(),row.eventType(),row.url(),row.secret(),row.payload()));
        });
    }

    public boolean complete(Attempt attempt,Result result,int maxAttempts) {
        return Boolean.TRUE.equals(tx.execute(s->{
            var active=jdbc.query("SELECT active FROM webhook_subscriptions WHERE id=? FOR SHARE",
                (rs,n)->rs.getBoolean("active"),attempt.subscriptionId());
            if(active.isEmpty())return false;
            var current=jdbc.query("SELECT id FROM webhook_deliveries WHERE id=? AND status='IN_FLIGHT' AND lease_token=? FOR UPDATE",
                (rs,n)->rs.getObject("id",UUID.class),attempt.id(),attempt.token());
            if(current.isEmpty())return false;
            String status=result.delivered()?"DELIVERED":!active.getFirst()?"CANCELED":
                result.permanent() || attempt.number()>=maxAttempts?"FAILED":"PENDING";
            long backoff=Math.min(60,1L<<Math.min(attempt.number()-1,6));
            jdbc.update("""
                UPDATE webhook_deliveries SET status=?,next_attempt_at=CASE WHEN ?='PENDING' THEN clock_timestamp()+(?*interval '1 second') ELSE NULL END,
                    lease_token=NULL,lease_until=NULL,finished_at=CASE WHEN ?='PENDING' THEN NULL ELSE clock_timestamp() END,
                    last_http_status=?,last_error=? WHERE id=? AND lease_token=? AND status='IN_FLIGHT'
                """,status,status,backoff,status,result.status(),status.equals("CANCELED")?"SUBSCRIPTION_DISABLED":result.error(),attempt.id(),attempt.token());
            jdbc.update("""
                UPDATE webhook_attempts SET outcome=?,finished_at=clock_timestamp(),http_status=?,error=?
                WHERE delivery_id=? AND attempt_number=? AND lease_token=? AND outcome='IN_FLIGHT'
                """,result.auditOutcome(),result.status(),result.error(),attempt.id(),attempt.number(),attempt.token());
            return true;
        }));
    }
}
