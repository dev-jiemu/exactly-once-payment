package dev.jiemu.payment.pg;

/**
 * PG 호출이 타임아웃/네트워크 오류로 끝나 승인 여부를 알 수 없는 경우.
 * 이 예외를 받은 호출자는 재승인하지 않고 UNKNOWN으로 기록해야 한다 — 확정은 Sweeper가 조회로 한다.
 */
public class PgAmbiguousException extends RuntimeException {

    public PgAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }
}
