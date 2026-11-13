package dev.payments.payment.webhooks;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookSignerTest {
    @Test void signsExactUtf8BytesAndTimestampWithKnownCrossLanguageVector() {
        String payload="{\"id\":\"123\",\"note\":\"café\"}",key="test-signing-key-0123456789abcdef";
        String signature=WebhookSigner.signature(1700000000L,payload,key);
        assertThat(signature).isEqualTo("t=1700000000,v1=f9114be66c3cf3cbe320a75f3af5df37eca31594a236a324fbd4142835bbecf4");
        assertThat(WebhookSigner.signature(1700000001L,payload,key)).isNotEqualTo(signature);
        assertThat(WebhookSigner.signature(1700000000L,payload+" ",key)).isNotEqualTo(signature);
    }
}
