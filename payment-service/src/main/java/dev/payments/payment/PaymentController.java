package dev.payments.payment;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentStore store;
    public PaymentController(PaymentStore store) { this.store=store; }
    @PostMapping
    public ResponseEntity<Payment> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreatePayment request) {
        if(!key.matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}")) throw new IllegalArgumentException("Invalid Idempotency-Key");
        var result=store.create(key,request);
        return ResponseEntity.status(result.inserted()?202:200)
                .location(URI.create("/payments/"+result.payment().id())).body(result.payment());
    }
    @GetMapping("/{id}") public Payment get(@PathVariable UUID id) { return store.get(id); }
}
