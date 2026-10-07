package dev.jiemu.payment.api;

import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;

import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        PaymentStatus status,
        String orderId,
        long amount,
        String failReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.paymentId().toString(),
                payment.status(),
                payment.orderId(),
                payment.amount(),
                payment.failReason(),
                payment.createdAt(),
                payment.updatedAt()
        );
    }
}
