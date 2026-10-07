package dev.jiemu.payment.api;

import dev.jiemu.payment.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedpandaContainer redpandaContainer;

    @Value("${payment.topics.requests}")
    private String requestsTopic;

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

    /**
     * 토픽 전체를 처음부터 읽어 이 테스트의 paymentId(= 메시지 key)에 해당하는 레코드만 걸러서 돌려준다.
     * 같은 컨테이너를 모든 테스트가 공유하므로, 토픽에는 다른 테스트가 발행한 메시지도 섞여 있다.
     * 반드시 메시지를 발행할 동작(POST 등)을 먼저 수행한 뒤 호출해야 한다
     * (토픽이 이미 존재해야 컨슈머 그룹 조인/파티션 할당이 지연 없이 끝난다).
     */
    private List<ConsumerRecord<String, String>> consumeRequestsTopic(String paymentId, int expectedAtLeast) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpandaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(requestsTopic));

            List<ConsumerRecord<String, String>> matched = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(record -> {
                    if (paymentId.equals(record.key())) {
                        matched.add(record);
                    }
                });
                if (matched.size() >= expectedAtLeast) {
                    // 같은 키로 더 발행된 메시지가 없는지 짧게 한 번 더 확인하고 끝낸다.
                    consumer.poll(Duration.ofSeconds(1)).forEach(record -> {
                        if (paymentId.equals(record.key())) {
                            matched.add(record);
                        }
                    });
                    break;
                }
            }
            return matched;
        }
    }

    @Test
    void 신규_결제_요청은_202와_함께_카프카에_발행된다() throws Exception {
        String merchantId = "merchant-1";
        String orderId = seedOrder(merchantId, 10_000L);
        String idempotencyKey = UUID.randomUUID().toString();

        String paymentId = extractPaymentId(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody(merchantId, orderId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andReturn().getResponse().getContentAsString());

        Integer count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM payment WHERE merchant_id = :merchantId AND idempotency_key = :key")
                .param("merchantId", merchantId)
                .param("key", idempotencyKey)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = consumeRequestsTopic(paymentId, 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).value()).contains(orderId);
    }

    @Test
    void 같은_키와_같은_요청의_재전송은_기존_결제를_그대로_반환하고_재발행하지_않는다() throws Exception {
        String merchantId = "merchant-2";
        String orderId = seedOrder(merchantId, 5_000L);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = requestBody(merchantId, orderId);

        String firstPaymentId = extractPaymentId(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        String secondPaymentId = extractPaymentId(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());

        assertThat(secondPaymentId).isEqualTo(firstPaymentId);

        Integer count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM payment WHERE merchant_id = :merchantId AND idempotency_key = :key")
                .param("merchantId", merchantId)
                .param("key", idempotencyKey)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);

        assertThat(consumeRequestsTopic(firstPaymentId, 1)).hasSize(1);
    }

    @Test
    void 같은_키에_다른_주문이면_409를_반환한다() throws Exception {
        String merchantId = "merchant-3";
        String orderId1 = seedOrder(merchantId, 1_000L);
        String orderId2 = seedOrder(merchantId, 2_000L);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody(merchantId, orderId1)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody(merchantId, orderId2)))
                .andExpect(status().isConflict());
    }

    @Test
    void 동시에_같은_키로_요청해도_결제는_하나만_생성된다() throws Exception {
        String merchantId = "merchant-4";
        String orderId = seedOrder(merchantId, 3_000L);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = requestBody(merchantId, orderId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> mockMvc.perform(post("/payments")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType("application/json")
                                    .content(body))
                            .andReturn().getResponse().getStatus()),
                    executor.submit(() -> mockMvc.perform(post("/payments")
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType("application/json")
                                    .content(body))
                            .andReturn().getResponse().getStatus())
            );
            for (Future<Integer> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(202);
            }
        } finally {
            executor.shutdown();
        }

        Integer count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM payment WHERE merchant_id = :merchantId AND idempotency_key = :key")
                .param("merchantId", merchantId)
                .param("key", idempotencyKey)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 존재하는_결제는_조회되고_없으면_404() throws Exception {
        String merchantId = "merchant-5";
        String orderId = seedOrder(merchantId, 7_000L);
        String idempotencyKey = UUID.randomUUID().toString();

        String paymentId = extractPaymentId(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody(merchantId, orderId)))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId));

        mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String requestBody(String merchantId, String orderId) throws Exception {
        return objectMapper.writeValueAsString(new CreatePaymentRequest(merchantId, orderId));
    }

    private String extractPaymentId(String json) throws Exception {
        return objectMapper.readTree(json).get("paymentId").asString();
    }
}
