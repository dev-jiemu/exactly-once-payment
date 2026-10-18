package dev.jiemu.payment.outbox;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
// 백그라운드 폴링을 꺼서 각 테스트가 relay()를 직접 호출해 타이밍을 제어한다.
@TestPropertySource(properties = { "payment.outbox.interval=1h" })
class OutboxRelayIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RedpandaContainer redpandaContainer;

    @Value("${payment.topics.requests}")
    private String requestsTopic;

    @Test
    void 미발행_이벤트는_relay_호출_시_카프카로_발행되고_published_at이_기록된다() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        outboxRepository.append(eventId, aggregateId, "PaymentRequested",
                "{\"eventId\":\"%s\",\"paymentId\":\"%s\"}".formatted(eventId, aggregateId));

        outboxRelay.relay();

        Boolean published = jdbcClient.sql(
                        "SELECT published_at IS NOT NULL FROM outbox_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
        assertThat(published).isTrue();

        List<ConsumerRecord<String, String>> records = consumeRequestsTopic(aggregateId.toString(), 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).value()).contains(eventId.toString());
    }

    @Test
    void 이미_발행된_이벤트는_relay를_다시_호출해도_재발행되지_않는다() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        outboxRepository.append(eventId, aggregateId, "PaymentRequested",
                "{\"eventId\":\"%s\",\"paymentId\":\"%s\"}".formatted(eventId, aggregateId));

        outboxRelay.relay();
        outboxRelay.relay();

        assertThat(consumeRequestsTopic(aggregateId.toString(), 1)).hasSize(1);
    }

    private List<ConsumerRecord<String, String>> consumeRequestsTopic(String key, int expectedAtLeast) {
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
                    if (key.equals(record.key())) {
                        matched.add(record);
                    }
                });
                if (matched.size() >= expectedAtLeast) {
                    consumer.poll(Duration.ofSeconds(1)).forEach(record -> {
                        if (key.equals(record.key())) {
                            matched.add(record);
                        }
                    });
                    break;
                }
            }
            return matched;
        }
    }
}
