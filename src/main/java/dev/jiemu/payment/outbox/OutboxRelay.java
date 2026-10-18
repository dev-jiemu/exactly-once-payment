package dev.jiemu.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

/**
 * outbox_event를 폴링해 Kafka로 발행하는 relay. 발행과 상태 변경(payment insert)을 같은 트랜잭션에 두는 대신,
 * DB에 먼저 기록하고 이 relay가 비동기로 발행해 dual-write(DB commit 성공 + Kafka send 실패로 인한 이벤트 유실)를 막는다.
 * 현재 이벤트 타입이 "PaymentRequested" 하나뿐이라 고정 토픽으로 발행한다 — 타입이 늘면 타입→토픽 매핑이 필요하다.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String requestsTopic;
    private final int batchSize;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payment.topics.requests}") String requestsTopic,
            @Value("${payment.outbox.batch-size}") int batchSize
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.requestsTopic = requestsTopic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.interval}")
    public void relay() {
        outboxRepository.findUnpublishedBatch(batchSize).forEach(this::relayOne);
    }

    private void relayOne(OutboxEvent event) {
        try {
            kafkaTemplate.send(requestsTopic, event.aggregateId().toString(), event.payload()).get();
            outboxRepository.markPublished(event.eventId());
        } catch (ExecutionException e) {
            log.warn("발행 실패, 다음 사이클에 재시도합니다: eventId={}", event.eventId(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("발행이 인터럽트되었습니다, 다음 사이클에 재시도합니다: eventId={}", event.eventId());
        }
    }
}
