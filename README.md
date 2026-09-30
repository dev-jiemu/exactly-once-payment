# exactly-once-payment

결제-PG 시나리오 : 멱등성 보장 설계 연습

분산 환경을 기준으로 결제 서버에서 **"같은 결제는 딱 한 번만 일어난다"** 를 보장하는 구조를 직접 구현해 보기

## 핵심 개념: exactly-once는 "전송"이 아니라 "효과"다

엄밀히 말해 분산 환경에서 **exactly-once 전송은 불가능** <br/>
네트워크는 언제든 요청이나 응답을 잃을 수 있고, 보낸 쪽은 "상대가 처리했는데 응답만 사라진 것"과 "아예 도착하지 않은 것"을 구분할 수 없습니다.
유실을 피하려면 다시 보내야 하고, 다시 보내면 중복이 생깁니다.

그래서 이 프로젝트가 실제로 하는 일은 다음과 같습니다.

```
at-least-once 전송  +  멱등 처리  =  exactly-once 효과
(유실 없이 재시도)     (중복은 무해하게)   (결과는 딱 한 번)
```

- **at-least-once 전송**: 클라이언트, 브로커, PG, 웹훅 모두 실패하면 다시 보냅니다. 유실보다 중복을 택합니다.
- **멱등 처리**: 같은 요청이 몇 번 도착하든 상태 변화와 부수효과는 한 번만 일어나도록 받는 쪽에서 막습니다.
- 결과적으로 사용자와 가맹점 입장에서는 **결제가 정확히 한 번 일어난 것처럼** 보입니다.


## 멱등성을 보장해야 하는 4개 지점

| 지점 | 중복/불확실성이 생기는 상황 | 이 프로젝트의 대응 |
|---|---|---|
| 클라이언트 재시도 | 더블클릭, 타임아웃 후 재전송 | `Idempotency-Key` 기반 결정적 `payment_id` + DB UNIQUE, 같은 키에 다른 payload면 거절 |
| 외부 PG 호출 | 타임아웃 후 승인 여부를 알 수 없음 | 항상 같은 `orderId`로만 요청, 불명확하면 재승인 대신 조회 API로 확정 (Sweeper) |
| 메시지 중복 소비 | 리밸런스, offset 커밋 전 장애 | 결제 PK 충돌로 중복 무시, outbox 발행 + 소비자 `processed_events` 테이블 |
| PG 웹훅 | 중복 전송, 순서 역전 | `webhook_inbox` UNIQUE insert 후 즉시 200, 상태 머신 CAS로 역전 이벤트 무시 |

모든 상태 전이는 `UPDATE … SET status = ? WHERE status IN (허용된 이전 상태)` 형태의 CAS로만 일어나며, 종결 상태(APPROVED / FAILED / CANCELED)는 바뀌지 않습니다.

```
REQUESTED → PROCESSING ─┬─→ APPROVED ─→ CANCEL_REQUESTED → CANCELED
                        ├─→ FAILED
                        └─→ UNKNOWN ──(조회)──→ APPROVED / FAILED
```

## 기술 스택

| 구분 | 선택 |
|---|---|
| 언어 / 프레임워크 | Java 21 (가상 스레드), Spring Boot 4.1 |
| 저장소 | PostgreSQL 18 (결제·주문·재고·outbox·inbox, 멱등 키 원천), Flyway |
| 메시지 브로커 | Redpanda 26.1 (Kafka API 호환) |
| 빌드 | Gradle 9.8 (Kotlin DSL), JDK 21 툴체인 |
| 테스트 | JUnit 5, Testcontainers 2 |

멱등성의 원천은 Redis가 아니라 **DB 유니크 제약 하나**로 둡니다. 멱등성 핵심 경로의 SQL(`ON CONFLICT`, CAS 업데이트)은 ORM 뒤에 숨기지 않고 드러나게 작성합니다.


## 진행 상황

- [x] README
- [x] 프로젝트 골격 (Gradle, docker-compose, DB 스키마, 패키지 구조)
- [ ] 클라이언트 재시도 멱등성
- [ ] 외부 PG 호출 멱등성 (+ Sweeper)
- [ ] 메시지 중복 소비 멱등성 (outbox / processed_events)
- [ ] PG 웹훅 멱등성
- [ ] 장애 시나리오 통합 테스트
