package dev.jiemu.payment.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OrderRepository {

    private final JdbcClient jdbcClient;

    public OrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<OrderRow> findById(String orderId) {
        return jdbcClient.sql("""
                        SELECT order_id, merchant_id, amount
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getString("order_id"),
                        rs.getString("merchant_id"),
                        rs.getLong("amount")
                ))
                .optional();
    }

    public record OrderRow(String orderId, String merchantId, long amount) {
    }
}
