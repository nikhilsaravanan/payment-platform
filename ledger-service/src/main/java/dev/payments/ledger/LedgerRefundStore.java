package dev.payments.ledger;

import dev.payments.contract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;

@Repository
public class LedgerRefundStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerStore ledger;
    public LedgerRefundStore(JdbcTemplate jdbc,TransactionTemplate tx,LedgerStore ledger) {
        this.jdbc=jdbc;this.tx=tx;this.ledger=ledger;
    }

    public RefundResult submit(RefundTransfer request) {
        var id=LedgerStore.parseId(request.getRefundId());var originalId=LedgerStore.parseId(request.getPaymentId());
        if(request.getAmountMinor()<1 || request.getAmountMinor()>1_000_000_000_000L || !request.getCurrency().equals("USD"))
            throw new IllegalArgumentException("Invalid refund money");
        return tx.execute(s->{
            int inserted=jdbc.update("""
                INSERT INTO refund_decisions(refund_id,original_payment_id,amount_minor,currency,outcome)
                VALUES (?,?,?,?,'PROCESSING') ON CONFLICT (refund_id) DO NOTHING
                """,id,originalId,request.getAmountMinor(),request.getCurrency());
            if(inserted==1) {
                var outcome=resolve(request);
                jdbc.update("UPDATE refund_decisions SET outcome=? WHERE refund_id=?",outcome.name(),id);
            }
            var result=jdbc.queryForObject("SELECT * FROM refund_decisions WHERE refund_id=?",(rs,n)->RefundResult.newBuilder()
                    .setRefundId(rs.getString("refund_id")).setPaymentId(rs.getString("original_payment_id"))
                    .setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency"))
                    .setOutcome(RefundOutcome.valueOf(rs.getString("outcome"))).build(),id);
            if(!result.getPaymentId().equals(request.getPaymentId()) || result.getAmountMinor()!=request.getAmountMinor()
                    || !result.getCurrency().equals(request.getCurrency())) throw new LedgerStore.Conflict();
            return result;
        });
    }

    private RefundOutcome resolve(RefundTransfer request) {
        // Serialize the original-payment cap before acquiring any account locks.
        var originals=jdbc.query("""
            SELECT j.* FROM journals j JOIN transfer_decisions d USING(payment_id)
            WHERE j.payment_id=?::uuid AND d.kind='TRANSFER' AND d.outcome='POSTED' FOR UPDATE OF j
            """,(rs,n)->Transfer.newBuilder().setPaymentId(rs.getString("payment_id"))
                .setSourceAccount(rs.getString("source_account")).setDestinationAccount(rs.getString("destination_account"))
                .setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency")).build(),request.getPaymentId());
        if(originals.isEmpty()) {
            boolean exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM transfer_decisions WHERE payment_id=?::uuid)",Boolean.class,request.getPaymentId()));
            return exists?RefundOutcome.REFUND_PAYMENT_NOT_REFUNDABLE:RefundOutcome.REFUND_PAYMENT_NOT_FOUND;
        }
        var original=originals.getFirst();
        var refunded=jdbc.queryForObject("SELECT COALESCE(sum(amount_minor::numeric),0) FROM refund_decisions WHERE original_payment_id=?::uuid AND outcome='REFUND_POSTED'",
                BigDecimal.class,request.getPaymentId());
        if(refunded.add(BigDecimal.valueOf(request.getAmountMinor())).compareTo(BigDecimal.valueOf(original.getAmountMinor()))>0)
            return RefundOutcome.REFUND_LIMIT_EXCEEDED;
        var reversal=Transfer.newBuilder().setPaymentId(request.getRefundId()).setSourceAccount(original.getDestinationAccount())
                .setDestinationAccount(original.getSourceAccount()).setAmountMinor(request.getAmountMinor()).setCurrency(original.getCurrency()).build();
        return switch(ledger.reverse(reversal).getOutcome()) {
            case POSTED -> RefundOutcome.REFUND_POSTED;
            case INSUFFICIENT_FUNDS -> RefundOutcome.REFUND_INSUFFICIENT_FUNDS;
            default -> throw new IllegalStateException("Original payment accounts must exist");
        };
    }
}
