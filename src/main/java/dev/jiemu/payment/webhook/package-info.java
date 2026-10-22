/**
 * [멱등성 4] PG 웹훅.
 * HMAC-SHA256 서명 검증 후 webhook_inbox에 insert(pg_event_id UNIQUE)하고 즉시 200을 응답한다.
 * 처리는 {@link dev.jiemu.payment.pg.PaymentApprovalService#resolve}의 상태 머신 CAS에 그대로 맡기며,
 * 순서가 역전된 이벤트는 허용되지 않는 전이라 무시된다.
 * APPROVED/FAILED 확정 이벤트만 다룬다 — CANCELED는 취소-요청(CANCEL_REQUESTED) 흐름 자체가
 * 아직 없어 범위에서 제외했다. FakePG가 실제로 비동기 웹훅을 쏘는 기능도 없다 — 이 패키지는
 * 수신측(멱등성 처리)만 책임지고, "PG가 웹훅을 보낸다"는 가정은 테스트에서 직접 호출로 대신한다.
 */
package dev.jiemu.payment.webhook;
