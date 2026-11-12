package dev.payments.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Repository
public class RefundStore {
    static final RowMapper<Refund> MAPPER=(rs,n)->new Refund(rs.getObject("id",UUID.class),rs.getObject("payment_id",UUID.class),
        rs.getLong("amount_minor"),rs.getString("currency"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toInstant(),
        rs.getTimestamp("rejected_at")==null?null:rs.getTimestamp("rejected_at").toInstant(),rs.getString("rejection_reason"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public RefundStore(JdbcTemplate jdbc,TransactionTemplate tx) {this.jdbc=jdbc;this.tx=tx;}

    public Created create(UUID paymentId,String key,long amount) {
        return tx.execute(s->{
            int inserted=jdbc.update("""
                INSERT INTO refunds(id,idempotency_key,payment_id,amount_minor,currency)
                SELECT ?,?,id,?,currency FROM payments WHERE id=? AND status='COMPLETED'
                ON CONFLICT (idempotency_key) DO NOTHING
                """,UUID.randomUUID(),key,amount,paymentId);
            var rows=jdbc.query("SELECT * FROM refunds WHERE idempotency_key=?",MAPPER,key);
            if(rows.isEmpty()) {
                if(jdbc.queryForObject("SELECT count(*) FROM payments WHERE id=?",Long.class,paymentId)==0)
                    throw new PaymentStore.NotFound();
                throw new NotRefundable();
            }
            var refund=rows.getFirst();
            if(!refund.paymentId().equals(paymentId) || refund.amountMinor()!=amount) throw new Conflict();
            return new Created(refund,inserted==1);
        });
    }
    public Refund get(UUID id) {
        var rows=jdbc.query("SELECT * FROM refunds WHERE id=?",MAPPER,id);
        if(rows.isEmpty())throw new NotFound();return rows.getFirst();
    }
    public record Created(Refund refund,boolean inserted) {}
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
    public static class NotRefundable extends RuntimeException {}
}
