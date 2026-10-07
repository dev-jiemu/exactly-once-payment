package dev.jiemu.payment.api;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /payments 요청 바디.
 * 금액은 클라이언트를 신뢰하지 않고 orderId로 조회한 주문 금액을 사용한다.
 */
public record CreatePaymentRequest(
        @NotBlank String merchantId,
        @NotBlank String orderId
) {
}
