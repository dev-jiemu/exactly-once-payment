package dev.jiemu.payment.pg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class HttpPgClient implements PgClient {

    private final RestClient restClient;

    public HttpPgClient(
            @Value("${payment.pg.base-url}") String baseUrl,
            @Value("${payment.pg.connect-timeout}") Duration connectTimeout,
            @Value("${payment.pg.read-timeout}") Duration readTimeout
    ) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PgResult approve(String orderId, long amount) {
        try {
            PgTransactionResponseBody response = restClient.post()
                    .uri("/payments/{orderId}/approve", orderId)
                    .body(new PgApproveRequestBody(amount))
                    .retrieve()
                    .body(PgTransactionResponseBody.class);
            return toResult(response);
        } catch (ResourceAccessException e) {
            throw new PgAmbiguousException("PG 승인 호출이 애매하게 끝났습니다: orderId=" + orderId, e);
        }
    }

    @Override
    public PgResult inquire(String orderId) {
        try {
            PgTransactionResponseBody response = restClient.get()
                    .uri("/payments/{orderId}", orderId)
                    .retrieve()
                    .body(PgTransactionResponseBody.class);
            return toResult(response);
        } catch (HttpClientErrorException.NotFound e) {
            return new PgResult(PgStatus.NOT_FOUND, null, null);
        } catch (ResourceAccessException e) {
            throw new PgAmbiguousException("PG 조회 호출이 애매하게 끝났습니다: orderId=" + orderId, e);
        }
    }

    @Override
    public PgResult cancel(String orderId) {
        try {
            PgTransactionResponseBody response = restClient.post()
                    .uri("/payments/{orderId}/cancel", orderId)
                    .retrieve()
                    .body(PgTransactionResponseBody.class);
            return toResult(response);
        } catch (HttpClientErrorException.NotFound e) {
            return new PgResult(PgStatus.NOT_FOUND, null, null);
        } catch (ResourceAccessException e) {
            throw new PgAmbiguousException("PG 취소 호출이 애매하게 끝났습니다: orderId=" + orderId, e);
        }
    }

    private static PgResult toResult(PgTransactionResponseBody response) {
        return new PgResult(PgStatus.valueOf(response.status()), response.tid(), response.failReason());
    }
}
