package dev.jiemu.payment.sweeper;

import dev.jiemu.payment.api.PaymentRepository;
import dev.jiemu.payment.domain.Payment;
import dev.jiemu.payment.domain.PaymentStatus;
import dev.jiemu.payment.pg.PaymentApprovalService;
import dev.jiemu.payment.pg.PgAmbiguousException;
import dev.jiemu.payment.pg.PgClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SweeperUnitTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PgClient pgClient;
    @Mock
    private PaymentApprovalService paymentApprovalService;

    private Sweeper sweeper;

    @BeforeEach
    void setUp() {
        // @Value 주입 없이 직접 생성
        sweeper = new Sweeper(paymentRepository, pgClient, paymentApprovalService, Duration.ofSeconds(20), 10);
    }

    @Test
    void PG_조회가_PgAmbiguousException이면_approve와_resolve를_호출하지_않고_다음_사이클로_넘긴다() {
        Payment payment = stubPayment(PaymentStatus.UNKNOWN);
        when(paymentRepository.claimUnresolvedBatch(any(), anyInt())).thenReturn(List.of(payment));
        when(pgClient.inquire(payment.orderId())).thenThrow(new PgAmbiguousException("PG 조회 타임아웃", null));

        sweeper.sweep();

        verify(paymentApprovalService, never()).approve(any());
        verify(paymentApprovalService, never()).resolve(any(), any());
    }

    @Test
    void 클레임된_행이_없으면_PG_클라이언트를_전혀_호출하지_않는다() {
        when(paymentRepository.claimUnresolvedBatch(any(), anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verifyNoInteractions(pgClient);
        verifyNoInteractions(paymentApprovalService);
    }

    private Payment stubPayment(PaymentStatus status) {
        Instant now = Instant.now();
        return new Payment(
                UUID.randomUUID(), "merchant-test", "key-test", "0".repeat(64),
                "order-" + UUID.randomUUID(), 1_000L, status,
                null, null, now, now
        );
    }
}
