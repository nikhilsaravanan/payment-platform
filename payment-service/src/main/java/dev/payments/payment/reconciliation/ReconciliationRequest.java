package dev.payments.payment.reconciliation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public record ReconciliationRequest(
    @NotBlank @Size(max=100) String accountId,
    @NotNull Instant from,
    @NotNull Instant to,
    @NotNull @Size(max=1000) List<@NotNull @Valid ProcessorRecord> records) {
    public enum OperationType { PAYMENT,REFUND }
    public record ProcessorRecord(
        @NotNull @Pattern(regexp="[\\x21-\\x7E][\\x20-\\x7E]{0,99}") String reference,
        @NotNull OperationType operationType,
        @NotNull UUID operationId,
        @NotNull @Min(1) @Max(1000000000000L) Long amountMinor,
        @NotNull @Pattern(regexp="[A-Z]{3}") String currency) {}
}
