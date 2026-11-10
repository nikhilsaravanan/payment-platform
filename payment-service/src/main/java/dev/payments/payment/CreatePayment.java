package dev.payments.payment;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record CreatePayment(
        @NotBlank @Size(max=100) String sourceAccount,
        @NotBlank @Size(max=100) String destinationAccount,
        @NotNull @Min(1) @Max(1_000_000_000_000L) Long amountMinor,
        @NotNull @Pattern(regexp="USD") String currency) {
    @AssertTrue(message="Source and destination must differ")
    @JsonIgnore
    public boolean isDistinctAccounts() { return sourceAccount==null || !sourceAccount.equals(destinationAccount); }
    @AssertTrue(message="Only customer accounts can create payments")
    @JsonIgnore
    public boolean isCustomerAccounts() {
        return !"__simulation_issuer__".equals(sourceAccount) && !"__simulation_issuer__".equals(destinationAccount);
    }
}
