package dev.payments.payment.webhooks;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Repository
public class WebhookSubscriptions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookTargets targets;
    public record Subscription(UUID id,String accountId,String url,boolean active,Instant createdAt,Instant disabledAt) {}
    public record Created(Subscription subscription,boolean inserted) {}
    static final RowMapper<Subscription> MAPPER=(rs,n)->new Subscription(rs.getObject("id",UUID.class),rs.getString("account_id"),
        rs.getString("url"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("disabled_at")==null?null:rs.getTimestamp("disabled_at").toInstant());
    public WebhookSubscriptions(JdbcTemplate jdbc,TransactionTemplate tx,WebhookTargets targets){this.jdbc=jdbc;this.tx=tx;this.targets=targets;}

    public Created create(String key,String account,String url,String secret) {
        String canonical=targets.requireAllowed(url).toASCIIString();
        return tx.execute(s->{
            int inserted=jdbc.update("INSERT INTO webhook_subscriptions(id,idempotency_key,account_id,url,signing_secret) VALUES (?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING",
                UUID.randomUUID(),key,account,canonical,secret);
            var row=jdbc.queryForObject("SELECT * FROM webhook_subscriptions WHERE idempotency_key=?",MAPPER,key);
            String stored=jdbc.queryForObject("SELECT signing_secret FROM webhook_subscriptions WHERE id=?",String.class,row.id());
            if(!row.accountId().equals(account) || !row.url().equals(canonical)
                || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))throw new Conflict();
            return new Created(row,inserted==1);
        });
    }
    public Subscription get(UUID id) {
        var rows=jdbc.query("SELECT * FROM webhook_subscriptions WHERE id=?",MAPPER,id);
        if(rows.isEmpty())throw new NotFound();return rows.getFirst();
    }
    public void disable(UUID id) {
        tx.executeWithoutResult(s->{
            get(id);jdbc.update("UPDATE webhook_subscriptions SET active=false,disabled_at=clock_timestamp() WHERE id=? AND active",id);
            jdbc.update("UPDATE webhook_deliveries SET status='CANCELED',finished_at=clock_timestamp(),next_attempt_at=NULL,last_error='SUBSCRIPTION_DISABLED' WHERE subscription_id=? AND status='PENDING'",id);
        });
    }
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
}
