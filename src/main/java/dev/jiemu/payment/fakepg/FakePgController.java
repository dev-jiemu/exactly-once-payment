package dev.jiemu.payment.fakepg;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.NoSuchElementException;

/**
 * 실제 PG 대신 로컬에서 띄우는 가짜 승인/조회/취소 API.
 * {@link FakePgScenarioRegistry}에 등록된 orderId별 장애(지연, 강제 결과)를 재현한다.
 */
@RestController
@RequestMapping("/fakepg/payments")
public class FakePgController {

    private final FakePgLedger ledger;
    private final FakePgScenarioRegistry scenarios;

    public FakePgController(FakePgLedger ledger, FakePgScenarioRegistry scenarios) {
        this.ledger = ledger;
        this.scenarios = scenarios;
    }

    @PostMapping("/{orderId}/approve")
    public ResponseEntity<FakePgTransactionResponse> approve(
            @PathVariable String orderId,
            @RequestBody FakePgApproveRequest request
    ) {
        FakePgScenario scenario = scenarios.resolve(orderId);

        if (scenario.fault() == FakePgFault.FAIL_IMMEDIATELY) {
            return ResponseEntity.ok(FakePgTransactionResponse.from(
                    ledger.approveIfAbsent(orderId, request.amount(), FakePgStatus.FAILED)));
        }

        sleep(scenario.delay());

        if (scenario.fault() == FakePgFault.TIMEOUT_NO_PERSIST) {
            // 타임아웃 난 경우는 처리가 정상적으로 되지 않았음으로 간주함
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        FakePgStatus outcome = scenario.fault() == FakePgFault.TIMEOUT_THEN_FAIL
                ? FakePgStatus.FAILED
                : FakePgStatus.APPROVED;
        return ResponseEntity.ok(FakePgTransactionResponse.from(
                ledger.approveIfAbsent(orderId, request.amount(), outcome)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<FakePgTransactionResponse> inquire(@PathVariable String orderId) {
        return ledger.find(orderId)
                .map(tx -> ResponseEntity.ok(FakePgTransactionResponse.from(tx)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<FakePgTransactionResponse> cancel(@PathVariable String orderId) {
        try {
            return ResponseEntity.ok(FakePgTransactionResponse.from(ledger.cancel(orderId)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private void sleep(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fakepg 지연 시뮬레이션이 중단되었습니다.", e);
        }
    }
}
