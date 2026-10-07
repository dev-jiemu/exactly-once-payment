package dev.jiemu.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

// 같은 (merchantId, idempotencyKey)는 항상 같은 payment_id를 가리키도록 구성
public final class PaymentIdGenerator {

    // 이 프로젝트 전용 namespace. 바꾸면 기존에 발급된 payment_id와 더 이상 일치하지 않는다.
    private static final UUID NAMESPACE = UUID.fromString("7f6e0f3a-3b7a-4e8e-9b0a-2b8e9f6b7c10");

    private PaymentIdGenerator() {
    }

    // 자바는 UUIDv5 제공 패키지가 없어서 만들어야 한다고 함 feat. Claude
    public static UUID generate(String merchantId, String idempotencyKey) {
        String name = merchantId + ":" + idempotencyKey;
        byte[] hash = sha1(concat(toBytes(NAMESPACE), name.getBytes(StandardCharsets.UTF_8)));

        hash[6] &= 0x0f;
        hash[6] |= 0x50; // version 5
        hash[8] &= 0x3f;
        hash[8] |= (byte) 0x80; // variant RFC 4122

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (hash[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 0; i < 8; i++) {
            bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return bytes;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
