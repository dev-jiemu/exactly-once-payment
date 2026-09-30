/**
 * [멱등성 3] 메시지 중복 소비 (요청 토픽).
 *
 * payment-requests를 배치로 소비해 {@code INSERT ... ON CONFLICT DO NOTHING}으로 중복을 제거하고,
 * 재고 차감과 PROCESSING 기록 후 PG를 호출한다. offset은 DB 반영 뒤 커밋한다.
 */
package dev.jiemu.payment.worker;
