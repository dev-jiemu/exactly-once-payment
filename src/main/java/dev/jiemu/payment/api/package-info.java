/**
 * [멱등성 1] 클라이언트 재시도.
 * {@code POST /payments} + {@code Idempotency-Key} 헤더를 받아 결정적 payment_id를 만들고
 * Redpanda 에 적재한 뒤 202를 응답한다. {@code GET /payments/{id}}로 결과를 폴링한다.
 */
package dev.jiemu.payment.api;
