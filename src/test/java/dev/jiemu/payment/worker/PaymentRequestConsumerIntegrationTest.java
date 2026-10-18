package dev.jiemu.payment.worker;

import dev.jiemu.payment.TestcontainersConfiguration;
import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import dev.jiemu.payment.fakepg.FakePgLedger;
import dev.jiemu.payment.fakepg.FakePgScenarioRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메시지 중복 소비 멱등성(worker) 통합 테스트.
 * fakepg가 같은 애플리케이션 안의 루프백 HTTP 서버라 실제 포트 바인딩이 필요해 DEFINED_PORT로 띄운다
 * (PgApprovalIntegrationTest와 같은 이유). 같은 포트를 쓰는 다른 DEFINED_PORT 컨텍스트와 캐시가 겹치지
 * 않도록 이 클래스가 끝나면 컨텍스트를 확실히 닫는다(@DirtiesContext).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "payment.sweeper.interval=1h" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRequestConsumerIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedpandaContainer redpandaContainer;

    @Autowired
    private FakePgScenarioRegistry scenarios;

    @Autowired
    private FakePgLedger fakePgLedger;

    @Value("${payment.topics.requests}")
    private String requestsTopic;

    @Value("${payment.worker.consumer-group}")
    private String consumerGroup;

    @BeforeEach
    void clearFakePgState() {
        scenarios.clear();
        fakePgLedger.clear();
    }

    @Test
    void 결제_요청_메시지를_소비하면_PROCESSING을_거쳐_APPROVED까지_처리된다() {
        String orderId = seedOrder("merchant-worker-1", 10_000L);
        Payment payment = seedRequestedPayment(orderId, "merchant-worker-1", 10_000L);
        UUID eventId = UUID.randomUUID();

        publish(eventId, payment.paymentId(), orderId, 10_000L);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
            assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        });

        Integer processedCount = jdbcClient.sql(
                        "SELECT COUNT(*) FROM processed_events WHERE consumer_group = :group AND event_id = :eventId")
                .param("group", consumerGroup)
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(processedCount).isEqualTo(1);
    }

    @Test
    void 같은_eventId가_중복_전달돼도_processed_events는_한_행만_남는다() {
        String orderId = seedOrder("merchant-worker-2", 5_000L);
        Payment payment = seedRequestedPayment(orderId, "merchant-worker-2", 5_000L);
        UUID eventId = UUID.randomUUID();

        publish(eventId, payment.paymentId(), orderId, 5_000L);
        publish(eventId, payment.paymentId(), orderId, 5_000L);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
            assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        });

        // 중복 메시지가 워커에 반영될 시간을 한 번 더 준 뒤, processed_events 행 수로 중복 소비 방지를 검증한다.
        Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer processedCount = jdbcClient.sql(
                            "SELECT COUNT(*) FROM processed_events WHERE consumer_group = :group AND event_id = :eventId")
                    .param("group", consumerGroup)
                    .param("eventId", eventId)
                    .query(Integer.class)
                    .single();
            assertThat(processedCount).isEqualTo(1);
        });
    }

    private void publish(UUID eventId, UUID paymentId, String orderId, long amount) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpandaContainer.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        String value = objectMapper.writeValueAsString(new PaymentRequestedMessage(eventId, paymentId, orderId, amount));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(requestsTopic, paymentId.toString(), value));
            producer.flush();
        }
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

    private Payment seedRequestedPayment(String orderId, String merchantId, long amount) {
        UUID paymentId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO payment
                            (payment_id, merchant_id, idempotency_key, request_hash, order_id, amount, status)
                        VALUES
                            (:paymentId, :merchantId, :idempotencyKey, :requestHash, :orderId, :amount, 'REQUESTED')
                        """)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .param("idempotencyKey", "key-" + paymentId)
                .param("requestHash", "0".repeat(64))
                .param("orderId", orderId)
                .param("amount", amount)
                .update();

        return paymentRepository.findById(paymentId).orElseThrow();
    }
}
