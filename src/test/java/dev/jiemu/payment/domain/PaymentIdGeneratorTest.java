package dev.jiemu.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIdGeneratorTest {

    @Test
    void 같은_입력은_항상_같은_UUID를_반환한다() {
        UUID first = PaymentIdGenerator.generate("merchant-1", "key-abc");
        UUID second = PaymentIdGenerator.generate("merchant-1", "key-abc");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void merchantId가_다르면_다른_UUID를_반환한다() {
        UUID a = PaymentIdGenerator.generate("merchant-1", "key-abc");
        UUID b = PaymentIdGenerator.generate("merchant-2", "key-abc");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void idempotencyKey가_다르면_다른_UUID를_반환한다() {
        UUID a = PaymentIdGenerator.generate("merchant-1", "key-abc");
        UUID b = PaymentIdGenerator.generate("merchant-1", "key-xyz");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 값이_같아도_merchantId와_idempotencyKey_순서가_바뀌면_다른_UUID가_나온다() {
        // "merchant-1":"key-abc" vs "key-abc":"merchant-1"
        UUID a = PaymentIdGenerator.generate("merchant-1", "key-abc");
        UUID b = PaymentIdGenerator.generate("key-abc", "merchant-1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 생성된_UUID의_버전은_5이다() {
        UUID uuid = PaymentIdGenerator.generate("merchant-1", "key-abc");
        assertThat(uuid.version()).isEqualTo(5);
    }

    @Test
    void 생성된_UUID의_variant는_RFC_4122이다() {
        // Java UUID.variant() = 2 → RFC 4122 (10b variant)
        UUID uuid = PaymentIdGenerator.generate("merchant-1", "key-abc");
        assertThat(uuid.variant()).isEqualTo(2);
    }
}
