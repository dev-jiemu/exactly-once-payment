package dev.jiemu.payment.api;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("결제 '%s'을(를) 찾을 수 없습니다.".formatted(paymentId));
    }
}
