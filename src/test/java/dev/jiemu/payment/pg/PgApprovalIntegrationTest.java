package dev.jiemu.payment.pg;

import dev.jiemu.payment.TestcontainersConfiguration;
import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import dev.jiemu.payment.fakepg.FakePgFault;
import dev.jiemu.payment.fakepg.FakePgLedger;
import dev.jiemu.payment.fakepg.FakePgScenario;
import dev.jiemu.payment.fakepg.FakePgScenarioRegistry;
import dev.jiemu.payment.sweeper.Sweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 PG 호출 멱등성(+ Sweeper) 통합 테스트.
 * worker가 아직 없으므로(다음 체크리스트 항목) PROCESSING 상태의 결제 행을 직접 심어두고
 * PaymentApprovalService / Sweeper를 바로 호출해 검증한다.
 *
 * fakepg는 같은 애플리케이션 안에서 뜨는 루프백 HTTP 서버이므로 실제 포트 바인딩이 필요해
 * DEFINED_PORT로 띄운다 — 같은 포트를 쓰는 다른 테스트 컨텍스트와 섞이지 않도록 이 클래스 하나에 시나리오를 모은다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payment.sweeper.unresolved-after=0s",
        "payment.sweeper.interval=1h"
})
class PgApprovalIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PgClient pgClient;

    @Autowired
    private PaymentApprovalService paymentApprovalService;

    @Autowired
    private Sweeper sweeper;

    @Autowired
    private FakePgScenarioRegistry scenarios;

    @Autowired
    private FakePgLedger fakePgLedger;

    /**
     * 각 테스트 전에 FakePg 상태를 초기화한다.
     * 특히 TIMEOUT_THEN_APPROVE 시나리오가 다음 테스트의 sweep에서 재발하지 않도록
     * 시나리오 레지스트리와 원장을 매 테스트마다 깨끗한 상태로 시작한다.
     */
    @BeforeEach
    void clearFakePgState() {
        scenarios.clear();
        fakePgLedger.clear();
    }

    @Test
    void 같은_orderId로_두번_승인해도_PG측_거래는_하나이고_같은_tid다() {
        String orderId = "order-" + UUID.randomUUID();

        PgResult first = pgClient.approve(orderId, 1_000L);
        PgResult second = pgClient.approve(orderId, 1_000L);

        assertThat(first.status()).isEqualTo(PgStatus.APPROVED);
        assertThat(second.pgTid()).isEqualTo(first.pgTid());
    }

    @Test
    void PG가_명확히_승인하면_PROCESSING에서_APPROVED로_전이된다() {
        String orderId = seedOrder("merchant-pg-1", 10_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-pg-1", 10_000L);

        paymentApprovalService.approve(payment);

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isNotBlank();
    }

    @Test
    void PG가_명확히_거절하면_PROCESSING에서_FAILED로_전이된다() {
        String orderId = seedOrder("merchant-pg-2", 5_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-pg-2", 5_000L);
        scenarios.register(orderId, FakePgScenario.fault(FakePgFault.FAIL_IMMEDIATELY, Duration.ZERO));

        paymentApprovalService.approve(payment);

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(updated.failReason()).isNotBlank();
    }

    @Test
    void 타임아웃이면_재승인하지_않고_UNKNOWN으로만_남긴다() {
        String orderId = seedOrder("merchant-pg-3", 7_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-pg-3", 7_000L);
        // read-timeout(3s)보다 긴 지연 — 클라이언트는 타임아웃으로 포기하지만 PG는 뒤늦게 실제로 승인을 완료한다.
        scenarios.register(orderId, FakePgScenario.fault(FakePgFault.TIMEOUT_THEN_APPROVE, Duration.ofSeconds(5)));

        paymentApprovalService.approve(payment);

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(updated.pgTid()).isNull();
    }

    @Test
    void Sweeper는_UNKNOWN이고_PG는_이미_승인된_건을_재승인_없이_APPROVED로_확정한다() {
        String orderId = seedOrder("merchant-sweep-1", 3_000L);
        // PG 쪽에 미리 승인 기록을 심어둔다 — "타임아웃 났지만 사실 PG는 처리를 끝낸" 상황 그 자체를 재현.
        PgResult approved = pgClient.approve(orderId, 3_000L);
        Payment payment = seedUnknownPayment(orderId, "merchant-sweep-1", 3_000L);
        // Docker 컨테이너와 호스트 간 clock skew로 인해 updated_at >= Instant.now()가 될 수 있으므로 명시적으로 과거로 backdating
        backdateUpdatedAt(payment.paymentId(), Duration.ofSeconds(1));

        sweeper.sweep();

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isEqualTo(approved.pgTid());
    }

    @Test
    void Sweeper는_PG에_기록이_없는_UNKNOWN_건을_재승인해서_확정한다() {
        String orderId = seedOrder("merchant-sweep-2", 4_000L);
        Payment payment = seedUnknownPayment(orderId, "merchant-sweep-2", 4_000L);
        // 이 orderId는 pgClient.approve가 아직 한 번도 성공한 적 없음(PG 원장에 기록 없음) → inquire는 NOT_FOUND를 반환해야 한다.
        backdateUpdatedAt(payment.paymentId(), Duration.ofSeconds(1));

        sweeper.sweep();

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isNotBlank();
    }

    @Test
    void 한번_클레임된_행은_같은_배치에서_다시_조회되지_않는다() {
        String orderId = seedOrder("merchant-sweep-3", 2_000L);
        Payment payment = seedUnknownPayment(orderId, "merchant-sweep-3", 2_000L);
        backdateUpdatedAt(payment.paymentId(), Duration.ofMinutes(1));

        // 운영 설정값(20s)과 같은 기준으로 판단: 클레임이 updated_at을 "지금"으로 갱신하므로,
        // 막 클레임된 행은 바로 다음 호출에서도 이 기준(20초 이전)을 더 이상 만족하지 못해야 한다.
        Instant threshold = Instant.now().minus(Duration.ofSeconds(20));
        List<Payment> firstClaim = paymentRepository.claimUnresolvedBatch(threshold, 10);
        List<Payment> secondClaim = paymentRepository.claimUnresolvedBatch(threshold, 10);

        assertThat(firstClaim).anyMatch(p -> p.orderId().equals(orderId));
        assertThat(secondClaim).noneMatch(p -> p.orderId().equals(orderId));
    }

    @Test
    void Sweeper는_PROCESSING_상태인_건도_PG_조회로_APPROVED로_확정한다() {
        String orderId = seedOrder("merchant-sweep-proc", 5_000L);
        // PG 쪽에 미리 승인 기록을 심어둔다 — "PROCESSING이지만 PG는 이미 승인 완료" 상황
        PgResult approved = pgClient.approve(orderId, 5_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-sweep-proc", 5_000L);
        backdateUpdatedAt(payment.paymentId(), Duration.ofSeconds(1));

        sweeper.sweep();

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isEqualTo(approved.pgTid());
    }

    @Test
    void CANCELED_PgResult로_resolve를_호출해도_PROCESSING_상태가_바뀌지_않는다() {
        // PaymentApprovalService.resolve()의 CANCELED/NOT_FOUND 분기 — 코드상 no-op이지만 미검증 상태
        String orderId = seedOrder("merchant-canceled", 6_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-canceled", 6_000L);

        paymentApprovalService.resolve(payment.paymentId(), new PgResult(PgStatus.CANCELED, null, null));

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.PROCESSING);

        // 테스트 격리: PROCESSING 상태가 이후 sweep에서 처리되지 않도록 FAILED로 전이한다
        paymentRepository.transitionTo(payment.paymentId(), List.of(PaymentStatus.PROCESSING), PaymentStatus.FAILED, null, null);
    }

    @Test
    void 이미_APPROVED된_결제에_resolve를_다시_호출해도_CAS_보호로_상태와_pgTid가_바뀌지_않는다() {
        String orderId = seedOrder("merchant-cas", 8_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-cas", 8_000L);

        PgResult firstApproval = pgClient.approve(orderId, 8_000L);
        paymentApprovalService.resolve(payment.paymentId(), firstApproval);

        Payment afterFirst = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(afterFirst.status()).isEqualTo(PaymentStatus.APPROVED);

        // 종결 상태(APPROVED)에서 resolve를 한 번 더 호출해도 상태 전이는 일어나지 않는다
        paymentApprovalService.resolve(payment.paymentId(), firstApproval);

        Payment afterSecond = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(afterSecond.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(afterSecond.pgTid()).isEqualTo(firstApproval.pgTid());
    }

    @Test
    void claimUnresolvedBatch는_limit_이상의_행을_반환하지_않는다() {
        int limit = 2;
        // limit + 1개의 UNKNOWN 결제를 심고 모두 오래된 것처럼 backdating 한다
        for (int i = 0; i < limit + 1; i++) {
            String orderId = seedOrder("merchant-batch-lim-" + i, 1_000L);
            Payment p = seedUnknownPayment(orderId, "merchant-batch-lim-" + i, 1_000L);
            backdateUpdatedAt(p.paymentId(), Duration.ofMinutes(5));
        }

        List<Payment> claimed = paymentRepository.claimUnresolvedBatch(
                Instant.now().minus(Duration.ofMinutes(1)), limit);

        assertThat(claimed).hasSize(limit);

        // 테스트 격리: batch-lim 결제를 FAILED로 처리해 이후 sweep에서 처리되지 않도록 정리한다
        jdbcClient.sql("UPDATE payment SET status = 'FAILED' WHERE merchant_id LIKE 'merchant-batch-lim-%'").update();
    }

    private void backdateUpdatedAt(UUID paymentId, Duration age) {
        jdbcClient.sql("UPDATE payment SET updated_at = :updatedAt WHERE payment_id = :paymentId")
                .param("updatedAt", java.sql.Timestamp.from(Instant.now().minus(age)))
                .param("paymentId", paymentId)
                .update();
    }

    private String seedOrder(String merchantId, long amount) {
        long productId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String orderId = "order-" + UUID.randomUUID();

        jdbcClient.sql("INSERT INTO product_stock (product_id, quantity) VALUES (:productId, 100)")
                .param("productId", productId)
                .update();

        jdbcClient.sql("""
                        INSERT INTO orders (order_id, merchant_id, product_id, quantity, amount)
                        VALUES (:orderId, :merchantId, :productId, 1, :amount)
                        """)
                .param("orderId", orderId)
                .param("merchantId", merchantId)
                .param("productId", productId)
                .param("amount", amount)
                .update();

        return orderId;
    }

    private Payment seedProcessingPayment(String orderId, String merchantId, long amount) {
        return seedPayment(orderId, merchantId, amount, PaymentStatus.PROCESSING);
    }

    private Payment seedUnknownPayment(String orderId, String merchantId, long amount) {
        return seedPayment(orderId, merchantId, amount, PaymentStatus.UNKNOWN);
    }

    private Payment seedPayment(String orderId, String merchantId, long amount, PaymentStatus status) {
        UUID paymentId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO payment
                            (payment_id, merchant_id, idempotency_key, request_hash, order_id, amount, status)
                        VALUES
                            (:paymentId, :merchantId, :idempotencyKey, :requestHash, :orderId, :amount, :status)
                        """)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .param("idempotencyKey", "key-" + paymentId)
                .param("requestHash", "0".repeat(64))
                .param("orderId", orderId)
                .param("amount", amount)
                .param("status", status.name())
                .update();

        return paymentRepository.findById(paymentId).orElseThrow();
    }
}
