package dev.jiemu.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    @Test
    void 같은_입력은_항상_같은_해시를_반환한다() {
        String first = RequestHasher.hash("merchant-1", "order-abc");
        String second = RequestHasher.hash("merchant-1", "order-abc");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void merchantId가_다르면_다른_해시를_반환한다() {
        String a = RequestHasher.hash("merchant-1", "order-abc");
        String b = RequestHasher.hash("merchant-2", "order-abc");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void orderId가_다르면_다른_해시를_반환한다() {
        String a = RequestHasher.hash("merchant-1", "order-abc");
        String b = RequestHasher.hash("merchant-1", "order-xyz");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 출력은_SHA_256에_맞는_64자리_소문자_16진수이다() {
        String hash = RequestHasher.hash("merchant-1", "order-abc");
        assertThat(hash)
                .hasSize(64)
                .matches("[0-9a-f]+");
    }

    @Test
    void 값이_같아도_merchantId와_orderId_순서가_바뀌면_다른_해시가_나온다() {
        // "merchantId=merchant-1&orderId=order-abc" vs "merchantId=order-abc&orderId=merchant-1"
        String a = RequestHasher.hash("merchant-1", "order-abc");
        String b = RequestHasher.hash("order-abc", "merchant-1");
        assertThat(a).isNotEqualTo(b);
    }
}
