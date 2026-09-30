/**
 * [멱등성 3] 후속 이벤트 발행과 소비.
 * outbox_event를 payment-events 토픽으로 발행하는 relay, 그리고 processed_events로 중복을 걸러내는 후속 소비자(알림/포인트 등)의 예시를 둔다.
 */
package dev.jiemu.payment.outbox;
