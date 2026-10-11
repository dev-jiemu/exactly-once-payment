package dev.jiemu.payment.sweeper;

import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.pg.PaymentApprovalService;
import dev.jiemu.payment.pg.PgAmbiguousException;
import dev.jiemu.payment.pg.PgClient;
import dev.jiemu.payment.pg.PgResult;
import dev.jiemu.payment.pg.PgStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 일정 시간 이상 PROCESSING / UNKNOWN에 머문 결제를 PG 조회로 확정한다.
 * PG에 기록이 없으면(승인 요청이 PG까지 도달하지 못했던 경우) 같은 orderId로 재승인한다 — PG 쪽 멱등성을 신뢰.
 */
@Component
public class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentApprovalService paymentApprovalService;
    private final Duration unresolvedAfter;
    private final int batchSize;

    public Sweeper(
            PaymentRepository paymentRepository,
            PgClient pgClient,
            PaymentApprovalService paymentApprovalService,
            @Value("${payment.sweeper.unresolved-after}") Duration unresolvedAfter,
            @Value("${payment.sweeper.batch-size}") int batchSize
    ) {
        this.paymentRepository = paymentRepository;
        this.pgClient = pgClient;
        this.paymentApprovalService = paymentApprovalService;
        this.unresolvedAfter = unresolvedAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.sweeper.interval}")
    public void sweep() {
        List<Payment> claimed = paymentRepository.claimUnresolvedBatch(Instant.now().minus(unresolvedAfter), batchSize);
        for (Payment payment : claimed) {
            resolveOne(payment);
        }
    }

    private void resolveOne(Payment payment) {
        PgResult inquiry;
        try {
            inquiry = pgClient.inquire(payment.orderId());
        } catch (PgAmbiguousException e) {
            log.warn("PG 조회가 애매하게 끝났습니다. 다음 사이클에 재시도합니다: paymentId={}", payment.paymentId());
            return;
        }

        if (inquiry.status() == PgStatus.NOT_FOUND) {
            paymentApprovalService.approve(payment);
            return;
        }
        paymentApprovalService.resolve(payment.paymentId(), inquiry);
    }
}
