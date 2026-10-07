package dev.jiemu.payment.api;

import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
