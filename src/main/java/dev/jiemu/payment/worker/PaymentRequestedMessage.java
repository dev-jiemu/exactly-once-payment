package dev.jiemu.payment.worker;

import java.util.UUID;

/**
 * outbox/PaymentService가 발행하는 "PaymentRequested" 이벤트의 payload.
 */
public record PaymentRequestedMessage(UUID eventId, UUID paymentId, String orderId, long amount) {
}
