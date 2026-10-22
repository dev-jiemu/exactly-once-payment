package dev.jiemu.payment.webhook;

import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.pg.PaymentApprovalService;
import dev.jiemu.payment.pg.PgResult;
import dev.jiemu.payment.pg.PgStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * webhook_inbox에 pg_event_id로 dedupe insert하고, 신규 이벤트면 결제 상태 전이를 적용한다.
 * 상태 전이는 {@link PaymentApprovalService#resolve}의 CAS에 그대로 맡긴다 — 순서가 역전된 이벤트는
 * 허용되지 않는 전이라 조용히 무시된다. CANCELED 웹훅은 다루지 않는다(취소-요청 흐름이 아직 없음).
 */
@Service
public class PgWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PgWebhookService.class);

    private final PaymentRepository paymentRepository;
    private final WebhookInboxRepository webhookInboxRepository;
    private final PaymentApprovalService paymentApprovalService;

    public PgWebhookService(
            PaymentRepository paymentRepository,
            WebhookInboxRepository webhookInboxRepository,
            PaymentApprovalService paymentApprovalService
    ) {
        this.paymentRepository = paymentRepository;
        this.webhookInboxRepository = webhookInboxRepository;
        this.paymentApprovalService = paymentApprovalService;
    }

    @Transactional
    public void handle(String rawBody, PgWebhookPayload payload) {
        Optional<Payment> payment = paymentRepository.findByOrderId(payload.orderId());

        boolean isNew = webhookInboxRepository.insertIfAbsent(
                payload.eventId(),
                payment.map(Payment::paymentId).orElse(null),
                payload.status(),
                rawBody);
        if (!isNew) {
            return;
        }

        if (payment.isEmpty()) {
            log.warn("웹훅이 가리키는 활성 결제를 찾을 수 없습니다: orderId={}, eventId={}",
                    payload.orderId(), payload.eventId());
            return;
        }

        paymentApprovalService.resolve(payment.get().paymentId(),
                new PgResult(PgStatus.valueOf(payload.status()), payload.tid(), payload.failReason()));
    }
}
