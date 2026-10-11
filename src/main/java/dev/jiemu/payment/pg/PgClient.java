package dev.jiemu.payment.pg;

/**
 * 승인/조회/취소 클라이언트. 항상 같은 orderId로만 요청한다 — PG 쪽이 orderId 기준으로 멱등하다는 전제.
 * 타임아웃/네트워크 오류로 결과가 애매하면 {@link PgAmbiguousException}을 던진다(재승인하지 않음).
 */
public interface PgClient {

    PgResult approve(String orderId, long amount);

    PgResult inquire(String orderId);

    PgResult cancel(String orderId);
}
