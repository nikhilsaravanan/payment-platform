package dev.payments.payment;

import dev.payments.contract.RefundOutcome;
import io.grpc.StatusRuntimeException;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="payments.worker.enabled",havingValue="true",matchIfMissing=true)
public class RefundWorker {
    private static final Logger log=LoggerFactory.getLogger(RefundWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerClient ledger;
    public RefundWorker(JdbcTemplate jdbc,TransactionTemplate tx,LedgerClient ledger) {this.jdbc=jdbc;this.tx=tx;this.ledger=ledger;}

    @Scheduled(fixedDelayString="${payments.worker.poll-millis:250}")
    public void tick() {
        try {for(int i=0;i<10 && processOne();i++) { /* each refund owns a bounded transaction */ }}
        catch(RuntimeException e) {log.error("Refund processing failed; durable work will be retried",e);}
    }
    public boolean processOne() {
        return Boolean.TRUE.equals(tx.execute(s->{
            var rows=jdbc.query("""
                SELECT * FROM refunds WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,RefundStore.MAPPER);
            if(rows.isEmpty())return false;var refund=rows.getFirst();
            try {
                var outcome=ledger.refund(refund);
                if(outcome==RefundOutcome.REFUND_POSTED)
                    jdbc.update("UPDATE refunds SET status='COMPLETED',completed_at=clock_timestamp(),last_error=NULL WHERE id=?",refund.id());
                else {
                    String reason=switch(outcome) {
                        case REFUND_INSUFFICIENT_FUNDS -> "INSUFFICIENT_FUNDS";
                        case REFUND_PAYMENT_NOT_FOUND -> "PAYMENT_NOT_FOUND";
                        case REFUND_PAYMENT_NOT_REFUNDABLE -> "PAYMENT_NOT_REFUNDABLE";
                        case REFUND_LIMIT_EXCEEDED -> "REFUND_LIMIT_EXCEEDED";
                        default -> throw new IllegalStateException("Unexpected refund outcome");
                    };
                    jdbc.update("UPDATE refunds SET status='REJECTED',rejected_at=clock_timestamp(),rejection_reason=?,last_error=NULL WHERE id=?",reason,refund.id());
                }
            } catch(StatusRuntimeException e) {
                log.warn("Ledger refund {} returned {}; scheduling retry",refund.id(),e.getStatus().getCode());
                jdbc.update("""
                    UPDATE refunds SET attempt_count=LEAST(attempt_count,2147483646)+1,
                        next_attempt_at=clock_timestamp()+(LEAST(60,power(2,LEAST(attempt_count,6)))*interval '1 second'),
                        last_error=? WHERE id=?
                    """,e.getStatus().getCode().name(),refund.id());
            }
            return true;
        }));
    }
}
