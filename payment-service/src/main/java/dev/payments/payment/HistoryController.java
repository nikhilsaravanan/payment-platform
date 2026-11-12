package dev.payments.payment;

import dev.payments.contract.LedgerTransaction;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class HistoryController {
    private final LedgerClient ledger;
    public HistoryController(LedgerClient ledger){this.ledger=ledger;}
    public record Transaction(UUID transactionId,String type,String accountId,String counterparty,String direction,
                              long amountMinor,String currency,Instant createdAt,UUID originalPaymentId) {
        static Transaction from(LedgerTransaction t) {
            return new Transaction(UUID.fromString(t.getTransactionId()),t.getType(),t.getAccountId(),t.getCounterparty(),t.getDirection(),
                t.getAmountMinor(),t.getCurrency(),Instant.parse(t.getCreatedAt()),t.getOriginalPaymentId().isEmpty()?null:UUID.fromString(t.getOriginalPaymentId()));
        }
    }
    public record Page(List<Transaction> items,String nextCursor) {}

    @GetMapping("/accounts/{accountId}/transactions")
    public Page list(@PathVariable String accountId,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="") String cursor) {
        if(accountId.isBlank() || accountId.length()>100 || accountId.equals("__simulation_issuer__") || limit<1 || limit>100 || cursor.length()>1024)
            throw new IllegalArgumentException("Invalid account history request");
        var page=ledger.history(accountId,limit,cursor);
        return new Page(page.getItemsList().stream().map(Transaction::from).toList(),page.getNextCursor().isEmpty()?null:page.getNextCursor());
    }
    @GetMapping("/transactions")
    public Page listByQuery(@RequestParam String accountId,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="") String cursor) {
        return list(accountId,limit,cursor);
    }
}
