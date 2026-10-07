package dev.jiemu.payment.api;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("주문 '%s'을(를) 찾을 수 없습니다.".formatted(orderId));
    }
}
