package dev.jiemu.payment.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * outbox_event 테이블 한 행을 그대로 옮긴 모델.
 */
public record OutboxEvent(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt
) {
}
