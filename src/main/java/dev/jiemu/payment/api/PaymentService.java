package dev.jiemu.payment.api;

import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentIdGenerator;
import dev.jiemu.payment.domain.RequestHasher;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRequestProducer paymentRequestProducer;

    public PaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentRequestProducer paymentRequestProducer
    ) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentRequestProducer = paymentRequestProducer;
    }

    public Payment requestPayment(String idempotencyKey, CreatePaymentRequest request) {
        OrderRepository.OrderRow order = orderRepository.findById(request.orderId())
                .filter(o -> o.merchantId().equals(request.merchantId()))
                .orElseThrow(() -> new OrderNotFoundException(request.orderId()));

        UUID paymentId = PaymentIdGenerator.generate(request.merchantId(), idempotencyKey);
        String requestHash = RequestHasher.hash(request.merchantId(), request.orderId());

        return paymentRepository.insertIfAbsent(
                        paymentId, request.merchantId(), idempotencyKey, requestHash, order.orderId(), order.amount())
                .map(inserted -> {
                    paymentRequestProducer.publishRequested(inserted);
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
}
