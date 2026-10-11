package dev.jiemu.payment.fakepg;

/**
 * 테스트가 특정 orderId에 주입하는 장애 종류.
 * TIMEOUT_* 계열은 delay가 클라이언트 read-timeout보다 길어야 실제로 타임아웃이 재현된다.
 */
public enum FakePgFault {
    /** 장애 없음. 즉시 승인. */
    NONE,
    /** 클라이언트는 타임아웃으로 포기하지만, PG는 지연 후 실제로 승인을 완료해 원장에 남긴다. */
    TIMEOUT_THEN_APPROVE,
    /** 클라이언트는 타임아웃으로 포기하지만, PG는 지연 후 실제로 거절을 확정해 원장에 남긴다. */
    TIMEOUT_THEN_FAIL,
    /** 클라이언트는 타임아웃으로 포기하고, PG도 요청을 원장에 남기지 못한 채 끊긴다(요청이 PG까지 도달 못 한 경우) */
    TIMEOUT_NO_PERSIST,
    /** 지연 없이 즉시 명확하게 거절한다. */
    FAIL_IMMEDIATELY
}
