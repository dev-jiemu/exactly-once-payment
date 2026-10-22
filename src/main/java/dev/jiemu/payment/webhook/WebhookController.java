package dev.jiemu.payment.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * PG 웹훅 수신. 서명 검증 후 webhook_inbox에 insert하고 즉시 200을 응답한다 — 중복/순서 역전 이벤트도
 * PG의 무한 재시도를 막기 위해 200으로 ack한다. 실제 상태 전이는 {@link PgWebhookService}의 CAS가 책임진다.
 */
@RestController
@RequestMapping("/webhooks/pg")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final PgWebhookService webhookService;
    private final ObjectMapper objectMapper;

    public WebhookController(
            WebhookSignatureVerifier signatureVerifier,
            PgWebhookService webhookService,
            ObjectMapper objectMapper
    ) {
        this.signatureVerifier = signatureVerifier;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String rawBody,
            @RequestHeader("X-PG-Signature") String signature
    ) {
        if (!signatureVerifier.verify(rawBody, signature)) {
            log.warn("PG 웹훅 서명이 일치하지 않습니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PgWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, PgWebhookPayload.class);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }

        webhookService.handle(rawBody, payload);
        return ResponseEntity.ok().build(); // 200
    }
}
