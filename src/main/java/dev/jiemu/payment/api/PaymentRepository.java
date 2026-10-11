package dev.jiemu.payment.api;

import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


// TODO : ORM
@Repository
public class PaymentRepository {

    private final JdbcClient jdbcClient;

    public PaymentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // insert 에 성공했다면 없던 데이터임
    public Optional<Payment> insertIfAbsent(
            UUID paymentId,
            String merchantId,
            String idempotencyKey,
            String requestHash,
            String orderId,
            long amount
    ) {
        return jdbcClient.sql("""
                        INSERT INTO payment
                            (payment_id, merchant_id, idempotency_key, request_hash, order_id, amount, status)
                        VALUES
                            (:paymentId, :merchantId, :idempotencyKey, :requestHash, :orderId, :amount, 'REQUESTED')
                        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                        RETURNING payment_id, merchant_id, idempotency_key, request_hash, order_id, amount,
                                  status, pg_tid, fail_reason, created_at, updated_at
                        """)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .param("orderId", orderId)
                .param("amount", amount)
                .query(PaymentRepository::mapRow)
                .optional();
    }

    public Optional<Payment> findByMerchantAndIdempotencyKey(String merchantId, String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT payment_id, merchant_id, idempotency_key, request_hash, order_id, amount,
                               status, pg_tid, fail_reason, created_at, updated_at
                        FROM payment
                        WHERE merchant_id = :merchantId AND idempotency_key = :idempotencyKey
                        """)
                .param("merchantId", merchantId)
                .param("idempotencyKey", idempotencyKey)
                .query(PaymentRepository::mapRow)
                .optional();
    }

    public Optional<Payment> findById(UUID paymentId) {
        return jdbcClient.sql("""
                        SELECT payment_id, merchant_id, idempotency_key, request_hash, order_id, amount,
                               status, pg_tid, fail_reason, created_at, updated_at
                        FROM payment
                        WHERE payment_id = :paymentId
                        """)
                .param("paymentId", paymentId)
                .query(PaymentRepository::mapRow)
                .optional();
    }

    // 상태 전이는 전부 이 메서드를 거친다: WHERE status IN (fromStatuses)로만 바뀌므로 허용되지 않은 전이는 조용히 0행 업데이트로 끝난다.
    public Optional<Payment> transitionTo(
            UUID paymentId,
            List<PaymentStatus> fromStatuses,
            PaymentStatus toStatus,
            String pgTid,
            String failReason
    ) {
        return jdbcClient.sql("""
                        UPDATE payment
                        SET status = :toStatus, pg_tid = :pgTid, fail_reason = :failReason, updated_at = now()
                        WHERE payment_id = :paymentId AND status IN (:fromStatuses)
                        RETURNING payment_id, merchant_id, idempotency_key, request_hash, order_id, amount,
                                  status, pg_tid, fail_reason, created_at, updated_at
                        """)
                .param("paymentId", paymentId)
                .param("toStatus", toStatus.name())
                .param("pgTid", pgTid)
                .param("failReason", failReason)
                .param("fromStatuses", fromStatuses.stream().map(Enum::name).toList())
                .query(PaymentRepository::mapRow)
                .optional();
    }

    // Sweeper용 배치 클레임: FOR UPDATE SKIP LOCKED로 고른 행을 같은 문장에서 updated_at까지 갱신한다
    // (조회와 PG 호출 사이에 DB 락을 들고 있지 않도록, 락은 이 한 문장 안에서만 존재한다).
    public List<Payment> claimUnresolvedBatch(Instant olderThan, int limit) {
        return jdbcClient.sql("""
                        WITH claimed AS (
                            SELECT payment_id
                            FROM payment
                            WHERE status IN ('PROCESSING', 'UNKNOWN') AND updated_at < :olderThan
                            ORDER BY updated_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE payment
                        SET updated_at = now()
                        FROM claimed
                        WHERE payment.payment_id = claimed.payment_id
                        RETURNING payment.payment_id, payment.merchant_id, payment.idempotency_key,
                                  payment.request_hash, payment.order_id, payment.amount, payment.status,
                                  payment.pg_tid, payment.fail_reason, payment.created_at, payment.updated_at
                        """)
                .param("olderThan", Timestamp.from(olderThan))
                .param("limit", limit)
                .query(PaymentRepository::mapRow)
                .list();
    }

    private static Payment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                (UUID) rs.getObject("payment_id"),
                rs.getString("merchant_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("order_id"),
                rs.getLong("amount"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("pg_tid"),
                rs.getString("fail_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
