/**
 * [멱등성 2] 미확정 결제 확정.
 * 일정 시간 이상 PROCESSING / UNKNOWN에 머문 결제를 PG 조회 API로 확정한다.
 * PG에 거래가 없으면 같은 orderId로 다시 승인을 요청한다.
 */
package dev.jiemu.payment.sweeper;
