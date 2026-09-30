package dev.jiemu.payment.domain;

/**
 * 결제 상태
 * REQUESTED → PROCESSING ─┬─→ APPROVED ─→ CANCEL_REQUESTED → CANCELED
 *                         ├─→ FAILED
 *                         └─→ UNKNOWN ──(조회)──→ APPROVED / FAILED
 * 종결 상태(APPROVED, FAILED, CANCELED)는 바뀌지 않는다.
 * 허용 전이 규칙은 직접 구현한다.
 */
public enum PaymentStatus {
    REQUESTED,
    PROCESSING,
    APPROVED,
    FAILED,
    UNKNOWN,
    CANCEL_REQUESTED,
    CANCELED
}
