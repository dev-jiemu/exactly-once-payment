/**
 * [멱등성 2] 외부 PG 호출.
 * 승인/조회/취소 클라이언트. 항상 같은 orderId(= payment_id)로만 요청하고, 타임아웃이면 재승인하지 않고 UNKNOWN으로 기록한다.
 */
package dev.jiemu.payment.pg;
