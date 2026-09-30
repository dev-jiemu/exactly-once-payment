package dev.jiemu.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * 통합 테스트용 인프라. docker-compose.yml과 같은 이미지를 쓴다.
 * {@code @ServiceConnection}이 datasource / kafka 접속 정보를 자동으로 덮어쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:18");
    }

    @Bean
    @ServiceConnection
    RedpandaContainer redpandaContainer() {
        return new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.2");
    }
}
