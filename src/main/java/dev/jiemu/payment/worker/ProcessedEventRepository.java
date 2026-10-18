package dev.jiemu.payment.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbcClient;

    public ProcessedEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // insert에 성공했다면(= 신규) true, 이미 처리된 이벤트라 충돌했다면 false.
    public boolean markProcessed(String consumerGroup, UUID eventId) {
        return jdbcClient.sql("""
                        INSERT INTO processed_events (consumer_group, event_id)
                        VALUES (:consumerGroup, :eventId)
                        ON CONFLICT DO NOTHING
                        RETURNING event_id
                        """)
                .param("consumerGroup", consumerGroup)
                .param("eventId", eventId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }
}
