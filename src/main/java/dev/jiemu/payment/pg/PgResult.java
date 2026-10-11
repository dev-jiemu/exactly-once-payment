package dev.jiemu.payment.pg;

public record PgResult(PgStatus status, String pgTid, String failReason) {
}
