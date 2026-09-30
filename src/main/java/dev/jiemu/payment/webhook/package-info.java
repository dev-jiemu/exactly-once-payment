/**
 * [멱등성 4] PG 웹훅.
 * 서명 검증 후 webhook_inbox에 insert(pg_event_id UNIQUE)하고 즉시 200을 응답한다.
 * 처리는 상태 머신 CAS로 하며, 순서가 역전된 이벤트는 허용되지 않는 전이라 무시된다.
 */
package dev.jiemu.payment.webhook;
