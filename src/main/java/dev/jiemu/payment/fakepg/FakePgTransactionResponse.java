package dev.jiemu.payment.fakepg;

public record FakePgTransactionResponse(
        String orderId,
        String tid,
        long amount,
        FakePgStatus status,
        String failReason
) {

    public static FakePgTransactionResponse from(FakePgTransaction tx) {
        return new FakePgTransactionResponse(tx.orderId(), tx.tid(), tx.amount(), tx.status(), tx.failReason());
    }
}
