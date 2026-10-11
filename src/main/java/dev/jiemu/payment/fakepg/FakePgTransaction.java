package dev.jiemu.payment.fakepg;

/**
 * 가짜 PG가 orderId별로 들고 있는 거래 원장 한 행.
 */
public record FakePgTransaction(
        String orderId,
        String tid,
        long amount,
        FakePgStatus status,
        String failReason
) {
}
