package dev.payments.payment.webhooks;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="webhooks.worker.enabled",havingValue="true",matchIfMissing=true)
public class WebhookWorker {
    private static final Logger log=LoggerFactory.getLogger(WebhookWorker.class);
    private final WebhookDeliveryStore store;
    private final WebhookHttp http;
    private final int maxAttempts;
    private final long leaseMillis;
    public WebhookWorker(WebhookDeliveryStore store,WebhookHttp http,@Value("${webhooks.max-attempts:5}") int maxAttempts,
                         @Value("${webhooks.lease-millis:30000}") long leaseMillis) {
        if(maxAttempts<1 || maxAttempts>20 || leaseMillis<=http.timeoutMillis() || leaseMillis>300000)
            throw new IllegalArgumentException("Invalid webhook attempt budget or lease");
        this.store=store;this.http=http;this.maxAttempts=maxAttempts;this.leaseMillis=leaseMillis;
    }
    @Scheduled(fixedDelayString="${webhooks.poll-millis:250}")
    public void tick() {
        try{for(int i=0;i<10 && processOne();i++){ /* each reservation commits before HTTP */ }}
        catch(RuntimeException e){log.error("Webhook processing failed; leased work remains durable",e);}
    }
    public boolean processOne() {
        var claim=store.claim(maxAttempts,leaseMillis);
        if(claim.attempt()==null)return claim.progressed();
        var result=http.send(claim.attempt());store.complete(claim.attempt(),result,maxAttempts);return true;
    }
}
