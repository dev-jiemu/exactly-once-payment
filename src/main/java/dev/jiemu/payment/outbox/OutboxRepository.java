package dev.jiemu.payment.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbcClient;

    public OutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Payment insert와 같은 트랜잭션에서 호출되어야 한다 (outbox 패턴의 핵심: 상태 변경과 이벤트 기록이 원자적으로 묶임).
    public void append(UUID eventId, UUID aggregateId, String eventType, String payloadJson) {
        jdbcClient.sql("""
                        INSERT INTO outbox_event (event_id, aggregate_id, event_type, payload)
                        VALUES (:eventId, :aggregateId, :eventType, :payload::jsonb)
                        """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    // 클레임 없이 단순 조회한다: 이 앱은 단일 인스턴스로만 떠서 Relay가 동시에 두 번 뛸 수 없고,
    // 재발행되어도 worker 쪽 processed_events가 중복을 흡수하므로 FOR UPDATE SKIP LOCKED가 필요 없다.
    public List<OutboxEvent> findUnpublishedBatch(int limit) {
        return jdbcClient.sql("""
                        SELECT event_id, aggregate_id, event_type, payload, created_at
                        FROM outbox_event
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(OutboxRepository::mapRow)
                .list();
    }

    public void markPublished(UUID eventId) {
        jdbcClient.sql("UPDATE outbox_event SET published_at = now() WHERE event_id = :eventId")
                .param("eventId", eventId)
                .update();
    }

    private static OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                (UUID) rs.getObject("event_id"),
                (UUID) rs.getObject("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
