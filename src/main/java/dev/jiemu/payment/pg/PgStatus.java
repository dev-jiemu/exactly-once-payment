package dev.jiemu.payment.pg;

public enum PgStatus {
    APPROVED,
    FAILED,
    CANCELED,
    /** PG에 해당 orderId 거래 기록이 전혀 없음(요청이 PG까지 도달하지 못한 경우). */
    NOT_FOUND
}
