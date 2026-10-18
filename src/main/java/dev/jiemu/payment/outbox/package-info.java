/**
 * [멱등성 3] Transactional Outbox.
 * payment insert와 같은 트랜잭션에서 outbox_event에 기록한 "PaymentRequested" 이벤트를,
 * {@link dev.jiemu.payment.outbox.OutboxRelay}가 폴링해 payment-requests 토픽으로 발행한다
 * (worker 패키지가 이 토픽을 소비한다).
 * payment-events 토픽 발행과 그걸 구독하는 후속 소비자(알림/포인트 등)는 이번 범위가 아니라 다음 체크리스트 항목으로 미룬다.
 */
package dev.jiemu.payment.outbox;
