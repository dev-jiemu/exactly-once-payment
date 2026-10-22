package dev.jiemu.payment.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class WebhookInboxRepository {

    private final JdbcClient jdbcClient;

    public WebhookInboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // insert에 성공했다면(= 신규 이벤트) true, 이미 받은 pg_event_id라 충돌했다면 false.
    // 처리가 이 메서드를 호출하는 트랜잭션 안에서 바로(동기) 끝나므로 processed_at도 즉시 채운다.
    public boolean insertIfAbsent(String pgEventId, UUID paymentId, String eventType, String payloadJson) {
        return jdbcClient.sql("""
                        INSERT INTO webhook_inbox (pg_event_id, payment_id, event_type, payload, processed_at)
                        VALUES (:pgEventId, :paymentId, :eventType, :payload::jsonb, now())
                        ON CONFLICT (pg_event_id) DO NOTHING
                        RETURNING id
                        """)
                .param("pgEventId", pgEventId)
                .param("paymentId", paymentId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .query(Long.class)
                .optional()
                .isPresent();
    }
}
