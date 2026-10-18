package dev.jiemu.payment.worker;

import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "처리됨" 표시(processed_events)와 PROCESSING 전이를 하나의 트랜잭션으로 묶어 원자성을 보장한다.
 * PG 승인 호출은 네트워크 I/O라 이 트랜잭션 밖에서 호출자(PaymentRequestConsumer)가 수행한다
 * (Sweeper가 세운 "조회/상태변경은 짧게, PG 호출은 트랜잭션 밖" 원칙과 동일).
 */
@Service
public class PaymentRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestHandler.class);

    private final ProcessedEventRepository processedEventRepository;
    private final PaymentRepository paymentRepository;
    private final String consumerGroup;

    public PaymentRequestHandler(
            ProcessedEventRepository processedEventRepository,
            PaymentRepository paymentRepository,
            @Value("${payment.worker.consumer-group}") String consumerGroup
    ) {
        this.processedEventRepository = processedEventRepository;
        this.paymentRepository = paymentRepository;
        this.consumerGroup = consumerGroup;
    }

    /**
     * 이미 처리된 eventId면 빈 값을 돌려준다(= 호출자는 PG를 부르지 않고 그냥 ack).
     * 신규 이벤트면 REQUESTED -> PROCESSING으로 전이한 Payment를 돌려준다.
     */
    @Transactional
    public Optional<Payment> handle(UUID eventId, UUID paymentId) {
        if (!processedEventRepository.markProcessed(consumerGroup, eventId)) {
            return Optional.empty();
        }

        Optional<Payment> processing = paymentRepository.transitionTo(
                paymentId, List.of(PaymentStatus.REQUESTED), PaymentStatus.PROCESSING, null, null);
        if (processing.isEmpty()) {
            // outbox가 payment당 PaymentRequested를 정확히 한 번만 append하므로 정상 흐름에서는 발생하지 않아야 한다.
            log.warn("신규 이벤트인데 결제가 REQUESTED 상태가 아니라 전이하지 못했습니다: paymentId={}", paymentId);
        }
        return processing;
    }
}
