package dev.payments.payment;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import dev.payments.contract.TransferOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="payments.worker.enabled",havingValue="true",matchIfMissing=true)
public class PaymentWorker {
    private static final Logger log=LoggerFactory.getLogger(PaymentWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerClient ledger;
    public PaymentWorker(JdbcTemplate jdbc,TransactionTemplate tx,LedgerClient ledger) {
        this.jdbc=jdbc;this.tx=tx;this.ledger=ledger;
    }

    @Scheduled(fixedDelayString="${payments.worker.poll-millis:250}")
    public void tick() {
        try { for(int i=0;i<10 && processOne();i++) { /* bounded batch, one transaction per payment */ } }
        catch(RuntimeException e) {log.error("Pending payment processing failed; durable work will be retried",e);}
    }

    public boolean processOne() {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var rows=jdbc.query("""
                SELECT * FROM payments WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """,PaymentStore.MAPPER);
            if(rows.isEmpty()) return false;
            var p=rows.getFirst();
            try {
                var outcome=ledger.post(p);
                if(outcome==TransferOutcome.POSTED)
                    jdbc.update("UPDATE payments SET status='COMPLETED',completed_at=clock_timestamp(),last_error=NULL WHERE id=?",p.id());
                else
                    jdbc.update("UPDATE payments SET status='REJECTED',rejected_at=clock_timestamp(),rejection_reason=?,last_error=NULL WHERE id=?",outcome.name(),p.id());
            } catch(StatusRuntimeException e) {
                var code=e.getStatus().getCode();
                if(code==Status.Code.INVALID_ARGUMENT || code==Status.Code.ALREADY_EXISTS)
                    log.error("Ledger rejected payment {} with {}; requires investigation",p.id(),code);
                else log.warn("Ledger request for payment {} returned {}; scheduling retry",p.id(),code);
                jdbc.update("""
                    UPDATE payments SET attempt_count=LEAST(attempt_count,2147483646)+1,
                        next_attempt_at=clock_timestamp()+ (LEAST(60,power(2,LEAST(attempt_count,6))) * interval '1 second'),
                        last_error=? WHERE id=?
                    """,code.name(),p.id());
            }
            return true;
        }));
    }
}
