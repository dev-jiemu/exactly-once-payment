package dev.jiemu.payment.fakepg;

import java.time.Duration;

public record FakePgScenario(FakePgFault fault, Duration delay) {

    public static FakePgScenario none() {
        return new FakePgScenario(FakePgFault.NONE, Duration.ZERO);
    }

    public static FakePgScenario fault(FakePgFault fault, Duration delay) {
        return new FakePgScenario(fault, delay);
    }
}
