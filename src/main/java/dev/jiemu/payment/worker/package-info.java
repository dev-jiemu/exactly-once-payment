/**
 * [멱등성 3] 메시지 중복 소비 (요청 토픽).
 *
 * payment-requests를 {@code @KafkaListener}로 단건 소비해, processed_events에
 * {@code INSERT ... ON CONFLICT DO NOTHING}으로 중복(at-least-once 재전달)을 걸러내고,
 * 신규 이벤트일 때만 PROCESSING 전이 후 PG를 호출한다({@link dev.jiemu.payment.worker.PaymentRequestHandler}).
 * offset은 DB 반영과 PG 호출이 모두 끝난 뒤 수동 커밋한다({@code ack-mode: manual}).
 * 재고 차감은 이번 범위가 아니라 다음 체크리스트 항목으로 미룬다.
 */
package dev.jiemu.payment.worker;
