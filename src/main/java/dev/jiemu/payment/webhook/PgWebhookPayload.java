package dev.jiemu.payment.webhook;

/**
 * PG가 보내는 웹훅 본문. {@code orderId}는 payment_id가 아니라 PG 승인 호출 때 쓴 orderId(= payment.order_id)다.
 */
record PgWebhookPayload(String eventId, String orderId, String status, String tid, String failReason) {
}
