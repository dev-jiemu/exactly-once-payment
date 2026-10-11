package dev.jiemu.payment.fakepg;

import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가짜 PG 쪽 원장. 실제 PG도 orderId 기준으로 멱등하다는 전제를 여기서 구현한다:
 * 같은 orderId로 몇 번을 승인 요청해도 이미 있는 거래면 그대로 반환한다(새 tid 발급 없음).
 */
@Component
public class FakePgLedger {

    private final ConcurrentHashMap<String, FakePgTransaction> transactions = new ConcurrentHashMap<>();

    public Optional<FakePgTransaction> find(String orderId) {
        return Optional.ofNullable(transactions.get(orderId));
    }

    // approveIfAbsent : 이미 있으면 기존 orderId 를 돌려줌 - 같은 번호로 중복된 승인 방지용
    public FakePgTransaction approveIfAbsent(String orderId, long amount, FakePgStatus outcome) {
        return transactions.computeIfAbsent(orderId, id -> new FakePgTransaction(
                id,
                UUID.randomUUID().toString(),
                amount,
                outcome,
                outcome == FakePgStatus.FAILED ? "가짜 PG가 승인을 거절했습니다." : null
        ));
    }

    public FakePgTransaction cancel(String orderId) {
        FakePgTransaction updated = transactions.computeIfPresent(orderId, (id, tx) ->
                new FakePgTransaction(tx.orderId(), tx.tid(), tx.amount(), FakePgStatus.CANCELED, tx.failReason()));
        if (updated == null) {
            throw new NoSuchElementException("가짜 PG에 거래가 없습니다: orderId=" + orderId);
        }
        return updated;
    }

    public void clear() {
        transactions.clear();
    }
}
