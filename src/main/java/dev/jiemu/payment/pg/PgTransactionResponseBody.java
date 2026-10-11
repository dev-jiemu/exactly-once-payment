package dev.jiemu.payment.pg;

record PgTransactionResponseBody(String orderId, String tid, long amount, String status, String failReason) {
}
