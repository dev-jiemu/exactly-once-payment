package dev.jiemu.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExactlyOncePaymentApplicationTests {

    @Test
    void contextLoads() {
        // TODO : 컨텍스트 기동 + Flyway 마이그레이션 적용까지 확인
    }
}
