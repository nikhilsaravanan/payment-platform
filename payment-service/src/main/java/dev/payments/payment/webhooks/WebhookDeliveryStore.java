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
}
