package dev.jiemu.payment.api;

/**
 * 같은 Idempotency-Key로 이전과 다른 요청(payload)이 들어온 경우.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '%s'는 이미 다른 요청에 사용되었습니다.".formatted(idempotencyKey));
    }
}
