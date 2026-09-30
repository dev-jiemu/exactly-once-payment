/**
 * 결제 도메인: 상태 머신, 결정적 payment_id 생성, 요청 해시.
 * 모든 상태 전이는 {@code UPDATE ... WHERE status IN (허용된 이전 상태)} 형태의 CAS로만 일어난다.
 */
package dev.jiemu.payment.domain;
