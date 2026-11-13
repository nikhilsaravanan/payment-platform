package dev.payments.payment.webhooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public final class WebhookSigner {
    private WebhookSigner() {}
    public static String signature(long timestamp,String payload,String secret) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException e){throw new IllegalStateException("HMAC-SHA256 unavailable",e);}
    }
}
