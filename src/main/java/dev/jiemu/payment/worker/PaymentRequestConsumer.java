package dev.jiemu.payment.worker;

import dev.jiemu.payment.pg.PaymentApprovalService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * payment-requests를 소비해 PROCESSING으로 전이하고 PG 승인을 호출한다.
 * 같은 eventId가 중복 전달돼도(리밸런스, 커밋 전 장애로 인한 at-least-once 재전달) processed_events가
 * {@link PaymentRequestHandler}에서 걸러내므로 approve가 실질적으로 한 번만 효과를 낸다.
 *
 * ⚠️ 확인 필요: 처리 중 예외가 나면 ack하지 않고 그대로 전파해 Spring Kafka 기본 에러 핸들러(무제한 재시도)에 맡긴다.
 * 매번 실패하는 메시지(poison pill)가 파티션을 막는 리스크는 이번 범위에서 다루지 않는다(DLT 등은 후속 과제).
 */
@Component
public class PaymentRequestConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentRequestHandler paymentRequestHandler;
    private final PaymentApprovalService paymentApprovalService;

    public PaymentRequestConsumer(
            ObjectMapper objectMapper,
            PaymentRequestHandler paymentRequestHandler,
            PaymentApprovalService paymentApprovalService
    ) {
        this.objectMapper = objectMapper;
        this.paymentRequestHandler = paymentRequestHandler;
        this.paymentApprovalService = paymentApprovalService;
    }

    @KafkaListener(topics = "${payment.topics.requests}", groupId = "${payment.worker.consumer-group}")
    public void onMessage(String value, Acknowledgment ack) {
        PaymentRequestedMessage message = objectMapper.readValue(value, PaymentRequestedMessage.class);

        paymentRequestHandler.handle(message.eventId(), message.paymentId())
                .ifPresent(paymentApprovalService::approve);

        ack.acknowledge();
    }
}
