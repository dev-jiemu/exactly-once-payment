package dev.jiemu.payment.webhook;

import dev.jiemu.payment.TestcontainersConfiguration;
import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PG 웹훅 멱등성 통합 테스트. 결제 상태 전이는 이미 PgApprovalIntegrationTest가 검증한
 * PaymentApprovalService.resolve()의 CAS에 맡기므로, 여기서는 "웹훅 경로"(서명 검증 → dedupe →
 * resolve 호출)가 올바르게 이어지는지만 확인한다. fakepg 루프백이 필요 없어 DEFINED_PORT가 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebhookControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${payment.webhook.secret}")
    private String webhookSecret;

    @Test
    void 정상_APPROVED_웹훅은_PROCESSING에서_APPROVED로_전이한다() throws Exception {
        String orderId = seedOrder("merchant-wh-1", 10_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-wh-1", 10_000L);
        String body = webhookBody(UUID.randomUUID().toString(), orderId, "APPROVED", "tid-1", null);

        postWebhook(body, sign(body)).andExpect(status().isOk());

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isEqualTo("tid-1");
    }

    @Test
    void 같은_eventId로_중복_전송해도_webhook_inbox에는_한_행만_남는다() throws Exception {
        String orderId = seedOrder("merchant-wh-2", 5_000L);
        seedProcessingPayment(orderId, "merchant-wh-2", 5_000L);
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId, orderId, "APPROVED", "tid-2", null);

        postWebhook(body, sign(body)).andExpect(status().isOk());
        postWebhook(body, sign(body)).andExpect(status().isOk());

        assertThat(countInboxByEventId(eventId)).isEqualTo(1);
    }

    @Test
    void 이미_APPROVED된_결제에_순서가_역전된_FAILED_웹훅이_도착해도_상태가_바뀌지_않는다() throws Exception {
        String orderId = seedOrder("merchant-wh-3", 7_000L);
        Payment payment = seedProcessingPayment(orderId, "merchant-wh-3", 7_000L);
        String approvedBody = webhookBody(UUID.randomUUID().toString(), orderId, "APPROVED", "tid-3", null);
        postWebhook(approvedBody, sign(approvedBody)).andExpect(status().isOk());

        String lateFailedBody = webhookBody(UUID.randomUUID().toString(), orderId, "FAILED", null, "뒤늦게 온 실패");
        postWebhook(lateFailedBody, sign(lateFailedBody)).andExpect(status().isOk());

        Payment updated = paymentRepository.findById(payment.paymentId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(updated.pgTid()).isEqualTo("tid-3");
    }

    @Test
    void 서명이_일치하지_않으면_401을_반환하고_아무것도_기록하지_않는다() throws Exception {
        String orderId = seedOrder("merchant-wh-4", 1_000L);
        seedProcessingPayment(orderId, "merchant-wh-4", 1_000L);
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId, orderId, "APPROVED", "tid-4", null);

        postWebhook(body, "0".repeat(64)).andExpect(status().isUnauthorized());

        assertThat(countInboxByEventId(eventId)).isZero();
    }

    @Test
    void 활성_결제가_없는_orderId의_웹훅도_200으로_응답하고_payment_id_없이_기록한다() throws Exception {
        String orphanOrderId = "order-orphan-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String body = webhookBody(eventId, orphanOrderId, "APPROVED", "tid-5", null);

        postWebhook(body, sign(body)).andExpect(status().isOk());

        Integer paymentIdIsNullCount = jdbcClient.sql(
                        "SELECT COUNT(*) FROM webhook_inbox WHERE pg_event_id = :eventId AND payment_id IS NULL")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(paymentIdIsNullCount).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body, String signature) throws Exception {
        return mockMvc.perform(post("/webhooks/pg")
                .header("X-PG-Signature", signature)
                .contentType("application/json")
                .content(body));
    }

    private int countInboxByEventId(String eventId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM webhook_inbox WHERE pg_event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String webhookBody(String eventId, String orderId, String status, String tid, String failReason) {
        return objectMapper.writeValueAsString(new PgWebhookPayload(eventId, orderId, status, tid, failReason));
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

    private Payment seedProcessingPayment(String orderId, String merchantId, long amount) {
        UUID paymentId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO payment
                            (payment_id, merchant_id, idempotency_key, request_hash, order_id, amount, status)
                        VALUES
                            (:paymentId, :merchantId, :idempotencyKey, :requestHash, :orderId, :amount, 'PROCESSING')
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
