package dev.jiemu.payment.api;

import tools.jackson.databind.ObjectMapper;
import dev.jiemu.payment.domain.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

// 결제 요청을 payment-requests 토픽에 적재함
@Component
public class PaymentRequestProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String requestsTopic;

    public PaymentRequestProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${payment.topics.requests}") String requestsTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.requestsTopic = requestsTopic;
    }

    public void publishRequested(Payment payment) {
        String key = payment.paymentId().toString();
        String value = serialize(payment);
        kafkaTemplate.send(requestsTopic, key, value);
    }

    private String serialize(Payment payment) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "paymentId", payment.paymentId().toString(),
                    "orderId", payment.orderId(),
                    "amount", payment.amount()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("결제 요청 이벤트를 직렬화하지 못했습니다: " + payment.paymentId(), e);
        }
    }
}
