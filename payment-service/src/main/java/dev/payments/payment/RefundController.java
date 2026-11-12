package dev.payments.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
public class RefundController {
    private final RefundStore store;
    public RefundController(RefundStore store) {this.store=store;}
    public record Request(@NotNull @Min(1) @Max(1_000_000_000_000L) Long amountMinor) {}

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<Refund> create(@PathVariable UUID paymentId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Request request) {
        if(!key.matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}")) throw new IllegalArgumentException("Invalid Idempotency-Key");
        var result=store.create(paymentId,key,request.amountMinor());
        return ResponseEntity.status(result.inserted()?202:200).location(URI.create("/refunds/"+result.refund().id())).body(result.refund());
    }
    @GetMapping("/refunds/{id}")
    public Refund get(@PathVariable UUID id) {return store.get(id);}
}
