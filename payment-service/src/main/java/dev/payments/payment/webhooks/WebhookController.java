package dev.payments.payment.webhooks;

import dev.payments.payment.LedgerClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
public class WebhookController {
    private final WebhookSubscriptions subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final WebhookTargets targets;
    private final LedgerClient ledger;
    public WebhookController(WebhookSubscriptions subscriptions,WebhookDeliveryStore deliveries,WebhookTargets targets,LedgerClient ledger) {
        this.subscriptions=subscriptions;this.deliveries=deliveries;this.targets=targets;this.ledger=ledger;
    }
    public record Subscribe(@NotBlank @Size(max=100) String accountId,@NotBlank @Size(max=2048) String url,
        @NotNull @Pattern(regexp="[\\x21-\\x7E]{32,256}") String signingSecret) {
        @Override public String toString(){return "WebhookSubscriptionRequest[redacted]";}
    }
    @PostMapping("/webhook-subscriptions")
    public ResponseEntity<WebhookSubscriptions.Subscription> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Subscribe request) {
        if(!key.matches("[\\x21-\\x7E][\\x20-\\x7E]{0,199}") || request.accountId().equals("__simulation_issuer__"))
            throw new IllegalArgumentException("Invalid subscription request");
        var existing=subscriptions.replay(key,request.accountId(),request.url(),request.signingSecret());
        if(existing!=null)return ResponseEntity.ok().location(URI.create("/webhook-subscriptions/"+existing.id())).body(existing);
        targets.requireAllowed(request.url());ledger.getAccount(request.accountId());
        var created=subscriptions.create(key,request.accountId(),request.url(),request.signingSecret());
        return ResponseEntity.status(created.inserted()?201:200).location(URI.create("/webhook-subscriptions/"+created.subscription().id())).body(created.subscription());
    }
    @GetMapping("/webhook-subscriptions/{id}") public WebhookSubscriptions.Subscription get(@PathVariable UUID id){return subscriptions.get(id);}
    @DeleteMapping("/webhook-subscriptions/{id}") public ResponseEntity<Void> disable(@PathVariable UUID id){subscriptions.disable(id);return ResponseEntity.noContent().build();}
    @GetMapping("/webhook-subscriptions/{id}/deliveries")
    public List<WebhookDeliveryStore.Delivery> list(@PathVariable UUID id,@RequestParam(defaultValue="20") int limit){subscriptions.get(id);return deliveries.list(id,limit);}
    @GetMapping("/webhook-deliveries/{id}") public WebhookDeliveryStore.Delivery delivery(@PathVariable UUID id){return deliveries.get(id);}
    @GetMapping("/webhook-deliveries/{id}/attempts") public List<WebhookDeliveryStore.AttemptView> attempts(@PathVariable UUID id){return deliveries.attempts(id);}
    @GetMapping("/webhook-events/{id}") public ResponseEntity<String> event(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(new MediaType("application","json",StandardCharsets.UTF_8)).body(deliveries.event(id));
    }
}
