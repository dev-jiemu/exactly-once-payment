package dev.jiemu.payment.pg;

import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * PG 승인 호출과 그 결과에 따른 상태 전이를 담당한다.
 * 호출 시점에 결제가 PROCESSING이어야 한다는 전제는 이 서비스를 부르는 쪽(향후 worker)의 책임이다.
 */
@Service
public class PaymentApprovalService {

    private static final List<PaymentStatus> UNRESOLVED = List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);

    private final PgClient pgClient;
    private final PaymentRepository paymentRepository;

    public PaymentApprovalService(PgClient pgClient, PaymentRepository paymentRepository) {
        this.pgClient = pgClient;
        this.paymentRepository = paymentRepository;
    }

    /**
     * PG에 승인을 요청한다. 결과가 애매하면(타임아웃/네트워크 오류) 재승인하지 않고 UNKNOWN으로 남긴다 — 확정은 Sweeper가 한다.
     */
    public void approve(Payment payment) {
        try {
            resolve(payment.paymentId(), pgClient.approve(payment.orderId(), payment.amount()));
        } catch (PgAmbiguousException e) {
            paymentRepository.transitionTo(payment.paymentId(), UNRESOLVED, PaymentStatus.UNKNOWN, null, null);
        }
    }

    /**
     * PG가 돌려준 명확한 결과(승인/실패)를 결제 상태에 반영한다.
     * PROCESSING(방금 승인 호출)과 UNKNOWN(Sweeper가 나중에 조회로 확정) 양쪽 모두에서 호출된다.
     */
    public void resolve(UUID paymentId, PgResult result) {
        switch (result.status()) {
            case APPROVED -> paymentRepository.transitionTo(
                    paymentId, UNRESOLVED, PaymentStatus.APPROVED, result.pgTid(), null);
            case FAILED -> paymentRepository.transitionTo(
                    paymentId, UNRESOLVED, PaymentStatus.FAILED, null, result.failReason());
            case CANCELED, NOT_FOUND -> {
                // 승인 흐름에서는 나올 수 없는 결과다. 아무것도 하지 않고 다음 Sweeper 사이클에 맡긴다.
            }
        }
    }
}
