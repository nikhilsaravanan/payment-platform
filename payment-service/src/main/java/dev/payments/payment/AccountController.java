package dev.payments.payment;

import dev.payments.contract.Account;
import dev.payments.contract.TransferOutcome;
import io.grpc.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigInteger;

@RestController
public class AccountController {
    private final LedgerClient ledger;
    public AccountController(LedgerClient ledger) { this.ledger=ledger; }

    public record CreateAccount(@NotBlank @Size(max=100) String accountId,
                                @NotNull @Pattern(regexp="USD") String currency) {}
    public record FundAccount(@NotBlank @Size(max=100) String accountId,
                              @NotNull @Min(1) @Max(1_000_000_000_000L) Long amountMinor,
                              @NotNull @Pattern(regexp="USD") String currency) {}
    public record Balance(String accountId,String currency,BigInteger balanceMinor) {
        static Balance from(Account account) {
            return new Balance(account.getAccountId(),account.getCurrency(),new BigInteger(account.getBalanceMinor()));
        }
    }
    public record Funding(String fundingId,String accountId,long amountMinor,String currency) {}

    @PostMapping("/accounts")
    public ResponseEntity<Balance> create(@Valid @RequestBody CreateAccount request) {
        var result=ledger.createAccount(request.accountId(),request.currency());
        return ResponseEntity.status(result.getCreated()?201:200).body(Balance.from(result.getAccount()));
    }
    @GetMapping("/accounts/{accountId}")
    public Balance get(@PathVariable String accountId) { return Balance.from(ledger.getAccount(accountId)); }
    // Query form supports opaque IDs containing slashes, which servlet path routing cannot safely accept.
    @GetMapping("/accounts")
    public Balance getByQuery(@RequestParam String accountId) { return get(accountId); }

    @PostMapping("/funding")
    public ResponseEntity<Funding> fund(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody FundAccount request) {
        if(!key.matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}")) throw new IllegalArgumentException("Invalid Idempotency-Key");
        var result=ledger.fund(key,request.accountId(),request.amountMinor(),request.currency());
        if(result.getOutcome()==TransferOutcome.ACCOUNT_NOT_FOUND) throw Status.NOT_FOUND.asRuntimeException();
        if(result.getOutcome()!=TransferOutcome.POSTED) throw Status.DATA_LOSS.asRuntimeException();
        return ResponseEntity.status(result.getCreated()?201:200).body(new Funding(result.getFundingId(),result.getAccountId(),
                result.getAmountMinor(),result.getCurrency()));
    }
}
