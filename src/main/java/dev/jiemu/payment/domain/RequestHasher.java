package dev.jiemu.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 같은 Idempotency-Key로 다른 요청이 들어오는지 감지하기 위해
 * 정규화된 요청 payload를 SHA-256으로 해시한다(DB의 request_hash CHAR(64)).
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(String merchantId, String orderId) {
        String normalized = "merchantId=" + merchantId + "&orderId=" + orderId;
        return sha256Hex(normalized);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
