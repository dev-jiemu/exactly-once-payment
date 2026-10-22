package dev.jiemu.payment.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PG 웹훅 서명을 검증한다. 원본 요청 바이트(rawBody)에 대한 HMAC-SHA256을 서명 당사자(PG)와
 * 같은 비밀키로 재계산해 헤더 값과 비교한다 — 바이트가 하나라도 다르면 서명이 깨진다.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public WebhookSignatureVerifier(@Value("${payment.webhook.secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    // 타이밍 공격을 피하기 위해 문자열 비교 대신 MessageDigest.isEqual로 바이트를 비교한다.
    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = hmac(rawBody);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signatureHeader);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
