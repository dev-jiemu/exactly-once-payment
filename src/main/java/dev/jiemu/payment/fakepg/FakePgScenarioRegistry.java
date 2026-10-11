package dev.jiemu.payment.fakepg;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트가 orderId별로 가짜 PG 장애를 등록해두는 곳.
 * 같은 JVM 안에서 뜨는 fakepg 컨트롤러이므로 테스트가 이 빈을 직접 주입받아 설정한다.
 */
@Component
public class FakePgScenarioRegistry {

    private final ConcurrentHashMap<String, FakePgScenario> scenarios = new ConcurrentHashMap<>();

    public void register(String orderId, FakePgScenario scenario) {
        scenarios.put(orderId, scenario);
    }

    public FakePgScenario resolve(String orderId) {
        return scenarios.getOrDefault(orderId, FakePgScenario.none());
    }

    public void clear() {
        scenarios.clear();
    }
}
