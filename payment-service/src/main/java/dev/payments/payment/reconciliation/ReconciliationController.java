package dev.payments.payment.reconciliation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/reconciliation-reports")
public class ReconciliationController {
    private final ReconciliationStore store;
    public ReconciliationController(ReconciliationStore store){this.store=store;}
    @PostMapping
    public ResponseEntity<ReconciliationStore.Report> create(@RequestHeader("Idempotency-Key") String key,
                                                            @Valid @RequestBody ReconciliationRequest request) {
        if(!key.matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}"))throw new IllegalArgumentException("Invalid idempotency key");
        var result=store.create(key,request);
        return ResponseEntity.status(result.inserted()?201:200).location(URI.create("/reconciliation-reports/"+result.report().id())).body(result.report());
    }
    @GetMapping("/{id}") public ReconciliationStore.Report get(@PathVariable UUID id){return store.get(id);}
    @GetMapping("/{id}/evidence") public ReconciliationStore.Evidence evidence(@PathVariable UUID id){return store.evidence(id);}
    @GetMapping("/{id}/findings")
    public ReconciliationStore.Page findings(@PathVariable UUID id,@RequestParam(defaultValue="20") int limit,
                                             @RequestParam(defaultValue="0") int after){return store.findings(id,limit,after);}
}
