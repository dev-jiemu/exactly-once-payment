package dev.jiemu.payment.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void 올바른_서명은_검증을_통과한다() throws Exception {
        String body = "{\"eventId\":\"evt-1\"}";

        assertThat(verifier.verify(body, hmac(SECRET, body))).isTrue();
    }

    @Test
    void 다른_비밀키로_만든_서명은_검증에_실패한다() throws Exception {
        String body = "{\"eventId\":\"evt-1\"}";

        assertThat(verifier.verify(body, hmac("wrong-secret", body))).isFalse();
    }

    @Test
    void 본문이_바뀌면_같은_서명이어도_검증에_실패한다() throws Exception {
        String original = "{\"eventId\":\"evt-1\"}";
        String tampered = "{\"eventId\":\"evt-2\"}";

        assertThat(verifier.verify(tampered, hmac(SECRET, original))).isFalse();
    }

    @Test
    void 서명_헤더가_없거나_빈값이면_검증에_실패한다() {
        String body = "{\"eventId\":\"evt-1\"}";

        assertThat(verifier.verify(body, null)).isFalse();
        assertThat(verifier.verify(body, "")).isFalse();
    }

    @Test
    void 서명_헤더가_16진수가_아니면_검증에_실패한다() {
        String body = "{\"eventId\":\"evt-1\"}";

        assertThat(verifier.verify(body, "not-hex!!")).isFalse();
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
