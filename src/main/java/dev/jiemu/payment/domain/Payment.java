package dev.jiemu.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * payment 테이블 한 행을 그대로 옮긴 모델. 영속화 로직은 각 패키지의 Repository가 담당한다.
 */
public record Payment(
        UUID paymentId,
        String merchantId,
        String idempotencyKey,
        String requestHash,
        String orderId,
        long amount,
        PaymentStatus status,
        String pgTid,
        String failReason,
        Instant createdAt,
        Instant updatedAt
) {
}
