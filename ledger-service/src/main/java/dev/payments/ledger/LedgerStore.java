package dev.payments.ledger;

import dev.payments.contract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Repository
public class LedgerStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public LedgerStore(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc;this.tx=tx; }

    public Journal post(Transfer t) {
        validate(t);
        return tx.execute(status -> {
            int inserted=jdbc.update("""
                INSERT INTO journals(payment_id,source_account,destination_account,amount_minor,currency)
                VALUES (?::uuid,?,?,?,?) ON CONFLICT (payment_id) DO NOTHING
                """,t.getPaymentId(),t.getSourceAccount(),t.getDestinationAccount(),t.getAmountMinor(),t.getCurrency());
            if(inserted==1) {
                jdbc.update("INSERT INTO entries VALUES (?::uuid,'DEBIT',?,?,?)",t.getPaymentId(),t.getSourceAccount(),t.getAmountMinor(),t.getCurrency());
                jdbc.update("INSERT INTO entries VALUES (?::uuid,'CREDIT',?,?,?)",t.getPaymentId(),t.getDestinationAccount(),t.getAmountMinor(),t.getCurrency());
            }
            var journal=get(t.getPaymentId());
            if(!journal.getTransfer().equals(t)) throw new Conflict();
            return journal;
        });
    }

    public Journal get(String paymentId) {
        UUID id=parseId(paymentId);
        var transfers=jdbc.query("SELECT * FROM journals WHERE payment_id=?",(rs,n)->Transfer.newBuilder()
                .setPaymentId(rs.getString("payment_id")).setSourceAccount(rs.getString("source_account"))
                .setDestinationAccount(rs.getString("destination_account")).setAmountMinor(rs.getLong("amount_minor"))
                .setCurrency(rs.getString("currency")).build(),id);
        if(transfers.isEmpty()) throw new NotFound();
        var entries=jdbc.query("SELECT * FROM entries WHERE payment_id=? ORDER BY CASE side WHEN 'DEBIT' THEN 0 ELSE 1 END",
                (rs,n)->Entry.newBuilder().setAccount(rs.getString("account")).setSide(rs.getString("side"))
                        .setAmountMinor(rs.getLong("amount_minor")).setCurrency(rs.getString("currency")).build(),id);
        return Journal.newBuilder().setTransfer(transfers.getFirst()).addAllEntries(entries).build();
    }

    private static void validate(Transfer t) {
        parseId(t.getPaymentId());
        if(t.getSourceAccount().isBlank() || t.getDestinationAccount().isBlank()
                || t.getSourceAccount().length()>100 || t.getDestinationAccount().length()>100
                || t.getSourceAccount().equals(t.getDestinationAccount())
                || t.getAmountMinor()<1 || t.getAmountMinor()>1_000_000_000_000L || !t.getCurrency().equals("USD"))
            throw new IllegalArgumentException("invalid transfer");
    }
    private static UUID parseId(String id) {
        var parsed=UUID.fromString(id);
        if(!parsed.toString().equals(id)) throw new IllegalArgumentException("invalid payment ID");
        return parsed;
    }
    public static class Conflict extends RuntimeException {}
    public static class NotFound extends RuntimeException {}
}
