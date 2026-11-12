package dev.payments.ledger;

import dev.payments.contract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigInteger;
import java.util.UUID;

@Repository
public class LedgerStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private record Decision(Transfer transfer, String kind, TransferOutcome outcome) {}
    private static final RowMapper<Decision> DECISION = (rs, n) -> new Decision(
            Transfer.newBuilder().setPaymentId(rs.getString("payment_id"))
                .setSourceAccount(rs.getString("source_account")).setDestinationAccount(rs.getString("destination_account"))
                .setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency")).build(),
            rs.getString("kind"), TransferOutcome.valueOf(rs.getString("outcome")));

    public LedgerStore(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

    /** Legacy journal API: rejection is thrown only after its decision commits. */
    public Journal post(Transfer transfer) {
        var result = submit(transfer);
        if (result.getOutcome() != TransferOutcome.POSTED) throw new Rejected(result.getOutcome());
        return get(transfer.getPaymentId());
    }

    public TransferResult submit(Transfer transfer) {
        return submit(transfer,"TRANSFER");
    }

    TransferResult reverse(Transfer transfer) { return submit(transfer,"REFUND"); }

    private TransferResult submit(Transfer transfer,String kind) {
        validate(transfer);
        return tx.execute(status -> {
            int inserted = insertDecision(transfer, kind, null, "payment_id");
            if (inserted == 1) resolve(transfer, false);
            var decision = jdbc.queryForObject("SELECT * FROM transfer_decisions WHERE payment_id=?::uuid", DECISION, transfer.getPaymentId());
            if (!decision.kind().equals(kind) || !sameMovement(decision.transfer(), transfer)) throw new Conflict();
            return TransferResult.newBuilder().setPaymentId(transfer.getPaymentId()).setOutcome(decision.outcome()).build();
        });
    }

    public FundingResult fund(FundingRequest request) {
        AccountStore.validateId(request.getAccountId());
        validateAmount(request.getAmountMinor(), request.getCurrency());
        if (!request.getIdempotencyKey().matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}"))
            throw new IllegalArgumentException("Invalid funding idempotency key");
        var transfer = Transfer.newBuilder().setPaymentId(UUID.randomUUID().toString())
                .setSourceAccount(AccountStore.ISSUER).setDestinationAccount(request.getAccountId())
                .setAmountMinor(request.getAmountMinor()).setCurrency(request.getCurrency()).build();
        return tx.execute(status -> {
            int inserted = insertDecision(transfer, "FUNDING", request.getIdempotencyKey(), "kind,idempotency_key");
            if (inserted == 1) resolve(transfer, true);
            var decision = jdbc.queryForObject("SELECT * FROM transfer_decisions WHERE kind='FUNDING' AND idempotency_key=?", DECISION, request.getIdempotencyKey());
            if (!sameMovement(decision.transfer(), transfer)) throw new Conflict();
            return FundingResult.newBuilder().setFundingId(decision.transfer().getPaymentId())
                    .setAccountId(request.getAccountId()).setAmountMinor(request.getAmountMinor()).setCurrency(request.getCurrency())
                    .setOutcome(decision.outcome()).setCreated(inserted == 1).build();
        });
    }

    private int insertDecision(Transfer t, String kind, String key, String conflictColumns) {
        // conflictColumns is chosen only by the two internal callers, never request data.
        return jdbc.update("""
            INSERT INTO transfer_decisions(payment_id,kind,idempotency_key,source_account,destination_account,amount_minor,currency,outcome)
            VALUES (?::uuid,?,?,?,?,?,?,'PROCESSING') ON CONFLICT (
            """ + conflictColumns + ") DO NOTHING", t.getPaymentId(), kind, key, t.getSourceAccount(), t.getDestinationAccount(), t.getAmountMinor(), t.getCurrency());
    }

    private void resolve(Transfer t, boolean funding) {
        // Immutable IDs and one shared order across both posting paths prevent lock cycles.
        var locked = jdbc.queryForList("SELECT account_id FROM accounts WHERE account_id IN (?,?) ORDER BY account_id FOR UPDATE",
                String.class, t.getSourceAccount(), t.getDestinationAccount());
        TransferOutcome outcome;
        if (locked.size() != 2) outcome = TransferOutcome.ACCOUNT_NOT_FOUND;
        else if (!funding && AccountStore.balance(jdbc, t.getSourceAccount()).compareTo(BigInteger.valueOf(t.getAmountMinor())) < 0)
            outcome = TransferOutcome.INSUFFICIENT_FUNDS;
        else {
            jdbc.update("""
                INSERT INTO journals(payment_id,source_account,destination_account,amount_minor,currency)
                VALUES (?::uuid,?,?,?,?)
                """, t.getPaymentId(), t.getSourceAccount(), t.getDestinationAccount(), t.getAmountMinor(), t.getCurrency());
            jdbc.update("INSERT INTO entries VALUES (?::uuid,'DEBIT',?,?,?)", t.getPaymentId(), t.getSourceAccount(), t.getAmountMinor(), t.getCurrency());
            jdbc.update("INSERT INTO entries VALUES (?::uuid,'CREDIT',?,?,?)", t.getPaymentId(), t.getDestinationAccount(), t.getAmountMinor(), t.getCurrency());
            outcome = TransferOutcome.POSTED;
        }
        jdbc.update("UPDATE transfer_decisions SET outcome=? WHERE payment_id=?::uuid", outcome.name(), t.getPaymentId());
    }

    public Journal get(String paymentId) {
        UUID id = parseId(paymentId);
        var transfers = jdbc.query("SELECT * FROM journals WHERE payment_id=?", (rs, n) -> Transfer.newBuilder()
                .setPaymentId(rs.getString("payment_id")).setSourceAccount(rs.getString("source_account"))
                .setDestinationAccount(rs.getString("destination_account")).setAmountMinor(rs.getLong("amount_minor"))
                .setCurrency(rs.getString("currency")).build(), id);
        if (transfers.isEmpty()) throw new NotFound();
        var entries = jdbc.query("SELECT * FROM entries WHERE payment_id=? ORDER BY CASE side WHEN 'DEBIT' THEN 0 ELSE 1 END",
                (rs, n) -> Entry.newBuilder().setAccount(rs.getString("account")).setSide(rs.getString("side"))
                        .setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency")).build(), id);
        return Journal.newBuilder().setTransfer(transfers.getFirst()).addAllEntries(entries).build();
    }

    private static boolean sameMovement(Transfer a, Transfer b) {
        return a.getSourceAccount().equals(b.getSourceAccount()) && a.getDestinationAccount().equals(b.getDestinationAccount())
                && a.getAmountMinor() == b.getAmountMinor() && a.getCurrency().equals(b.getCurrency());
    }
    private static void validate(Transfer t) {
        parseId(t.getPaymentId());
        AccountStore.validateId(t.getSourceAccount()); AccountStore.validateId(t.getDestinationAccount());
        if (t.getSourceAccount().equals(t.getDestinationAccount())) throw new IllegalArgumentException("Accounts must differ");
        validateAmount(t.getAmountMinor(), t.getCurrency());
    }
    private static void validateAmount(long amount, String currency) {
        if (amount < 1 || amount > 1_000_000_000_000L || !"USD".equals(currency)) throw new IllegalArgumentException("Invalid money");
    }
    static UUID parseId(String id) {
        var parsed = UUID.fromString(id);
        if (!parsed.toString().equals(id)) throw new IllegalArgumentException("Invalid payment ID");
        return parsed;
    }
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
    public static class Rejected extends RuntimeException {
        private final TransferOutcome outcome;
        public Rejected(TransferOutcome outcome) { this.outcome = outcome; }
        public TransferOutcome outcome() { return outcome; }
    }
}
