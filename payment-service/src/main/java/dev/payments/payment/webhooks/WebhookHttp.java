package dev.payments.payment.webhooks;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;

@Component
public class WebhookHttp implements AutoCloseable {
    private final WebhookTargets targets;
    private final HttpClient client;
    private final long timeoutMillis;
    public WebhookHttp(WebhookTargets targets,@Value("${webhooks.timeout-millis:2000}") long timeoutMillis) {
        if(timeoutMillis<50 || timeoutMillis>30000)throw new IllegalArgumentException("Webhook timeout must be 50..30000ms");
        this.targets=targets;this.timeoutMillis=timeoutMillis;
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(Math.min(timeoutMillis,1000))).build();
    }
    public long timeoutMillis(){return timeoutMillis;}
    public WebhookDeliveryStore.Result send(WebhookDeliveryStore.Attempt attempt) {
        java.net.URI target;
        try{target=targets.requireAllowed(attempt.url());}catch(IllegalArgumentException e){return WebhookDeliveryStore.Result.policy();}
        long timestamp=Instant.now().getEpochSecond();
        var request=HttpRequest.newBuilder(target).timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type","application/json").header("Webhook-Id",attempt.eventId().toString())
            .header("Webhook-Delivery-Id",attempt.id().toString()).header("Webhook-Subscription-Id",attempt.subscriptionId().toString())
            .header("Webhook-Attempt",Integer.toString(attempt.number())).header("Webhook-Signature",WebhookSigner.signature(timestamp,attempt.payload(),attempt.secret()))
            .POST(HttpRequest.BodyPublishers.ofString(attempt.payload(),StandardCharsets.UTF_8)).build();
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.discarding());
        try {
            int status=pending.get(timeoutMillis,TimeUnit.MILLISECONDS).statusCode();
            return status>=200 && status<300?WebhookDeliveryStore.Result.success(status):WebhookDeliveryStore.Result.httpError(status);
        } catch(TimeoutException e) {pending.cancel(true);return WebhookDeliveryStore.Result.network("NETWORK_TIMEOUT");}
        catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();return WebhookDeliveryStore.Result.network("NETWORK_INTERRUPTED");}
        catch(ExecutionException e){return WebhookDeliveryStore.Result.network(e.getCause() instanceof HttpTimeoutException?"NETWORK_TIMEOUT":"NETWORK_ERROR");}
    }
    @Override @PreDestroy public void close(){client.shutdownNow();}
}
