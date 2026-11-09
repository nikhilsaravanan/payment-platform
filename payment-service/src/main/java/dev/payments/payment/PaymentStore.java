package dev.payments.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Repository
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    static final RowMapper<Payment> MAPPER=(rs,n)->new Payment(rs.getObject("id",UUID.class),
            rs.getString("source_account"),rs.getString("destination_account"),rs.getLong("amount_minor"),
            rs.getString("currency"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at")==null ? null : rs.getTimestamp("completed_at").toInstant());

    public PaymentStore(JdbcTemplate jdbc,TransactionTemplate tx) { this.jdbc=jdbc;this.tx=tx; }
    public Created create(String key,CreatePayment request) {
        return tx.execute(s -> {
            int inserted=jdbc.update("""
                INSERT INTO payments(id,idempotency_key,source_account,destination_account,amount_minor,currency)
                VALUES (?,?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING
                """,UUID.randomUUID(),key,request.sourceAccount(),request.destinationAccount(),request.amountMinor(),request.currency());
            // Separate statement: READ COMMITTED sees the concurrent winner after ON CONFLICT waits.
            var payment=jdbc.queryForObject("SELECT * FROM payments WHERE idempotency_key=?",MAPPER,key);
            if(!payment.sourceAccount().equals(request.sourceAccount()) || !payment.destinationAccount().equals(request.destinationAccount())
                    || payment.amountMinor()!=request.amountMinor() || !payment.currency().equals(request.currency())) throw new Conflict();
            return new Created(payment,inserted==1);
        });
    }
    public Payment get(UUID id) {
        var payments=jdbc.query("SELECT * FROM payments WHERE id=?",MAPPER,id);
        if(payments.isEmpty()) throw new NotFound();
        return payments.getFirst();
    }
    public record Created(Payment payment,boolean inserted) {}
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
}
