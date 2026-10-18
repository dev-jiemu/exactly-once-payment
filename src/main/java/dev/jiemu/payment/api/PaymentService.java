package dev.jiemu.payment.api;

import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentIdGenerator;
import dev.jiemu.payment.domain.RequestHasher;
import dev.jiemu.payment.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // payment insert와 outbox_event insert를 같은 트랜잭션으로 묶는다 (outbox 패턴).
    // Kafka 발행은 OutboxRelay가 비동기로 하므로, 여기서 DB commit 이후 발행이 실패해 이벤트가 유실되는 일이 없다.
    @Transactional
    public Payment requestPayment(String idempotencyKey, CreatePaymentRequest request) {
        OrderRepository.OrderRow order = orderRepository.findById(request.orderId())
                .filter(o -> o.merchantId().equals(request.merchantId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));

        UUID paymentId = PaymentIdGenerator.generate(request.merchantId(), idempotencyKey);
        String requestHash = RequestHasher.hash(request.merchantId(), request.orderId());

        return paymentRepository.insertIfAbsent(
                        paymentId, request.merchantId(), idempotencyKey, requestHash, order.orderId(), order.amount())
                .map(inserted -> {
                    UUID eventId = UUID.randomUUID();
                    outboxRepository.append(eventId, inserted.paymentId(), "PaymentRequested",
                            serializePaymentRequested(eventId, inserted));
                    return inserted;
                })
                .orElseGet(() -> replay(request.merchantId(), idempotencyKey, requestHash));
    }

    public Payment getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId.toString()));
    }

    private Payment replay(String merchantId, String idempotencyKey, String requestHash) {
        Payment existing = paymentRepository.findByMerchantAndIdempotencyKey(merchantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "INSERT가 충돌했는데 기존 행을 찾을 수 없습니다: merchantId=%s, idempotencyKey=%s"
                                .formatted(merchantId, idempotencyKey)));

        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return existing;
    }

    private String serializePaymentRequested(UUID eventId, Payment payment) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "eventId", eventId.toString(),
                    "paymentId", payment.paymentId().toString(),
                    "orderId", payment.orderId(),
                    "amount", payment.amount()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("결제 요청 이벤트를 직렬화하지 못했습니다: " + payment.paymentId(), e);
        }
    }
}
