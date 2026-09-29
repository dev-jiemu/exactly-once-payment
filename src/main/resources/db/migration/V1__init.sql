-- =========================================================
-- exactly-once-payment 초기 스키마
-- 멱등성의 원천은 이 DB의 제약조건(PK / UNIQUE)이다.
-- =========================================================

-- 재고 (결제 확정 전 차감, 실패 시 복원)
CREATE TABLE product_stock (
    product_id  BIGINT      PRIMARY KEY,
    quantity    INT         NOT NULL CHECK (quantity >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 주문
CREATE TABLE orders (
    order_id    VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    product_id  BIGINT      NOT NULL REFERENCES product_stock (product_id),
    quantity    INT         NOT NULL CHECK (quantity > 0),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

--  결제
--  payment_id = UUIDv5(merchant_id + idempotency_key) : 같은 키로 재요청하면 같은 ID
--  PG 호출 시 orderId로 항상 payment_id를 사용한다 (중복요청이 될 수도 있으니)
CREATE TABLE payment (
    payment_id      UUID         PRIMARY KEY,
    merchant_id     VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,  -- 정규화한 요청 payload의 SHA-256
    order_id        VARCHAR(64)  NOT NULL REFERENCES orders (order_id),
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    status          VARCHAR(20)  NOT NULL,
    pg_tid          VARCHAR(100),           -- PG 거래 ID (승인 후 채워짐)
    fail_reason     VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_payment_idempotency UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT ck_payment_status CHECK (status IN (
        'REQUESTED', 'PROCESSING', 'APPROVED', 'FAILED', 'UNKNOWN',
        'CANCEL_REQUESTED', 'CANCELED'
    ))
);

-- 새 멱등 키로 같은 주문을 다시 결제하는 것 차단 (실패한 결제만 재시도 허용)
CREATE UNIQUE INDEX uk_payment_active_order
    ON payment (order_id)
    WHERE status NOT IN ('FAILED');

-- Sweeper: 오래 머문 미확정 건 조회
CREATE INDEX idx_payment_unresolved
    ON payment (updated_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN');

-- Transactional Outbox: 상태 변경과 같은 트랜잭션에서 기록, relay가 브로커로 발행
CREATE TABLE outbox_event (
    event_id     UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,     -- payment_id
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;

-- PG 웹훅 inbox: 같은 pg_event_id는 한 번만 저장된다
CREATE TABLE webhook_inbox (
    id           BIGSERIAL    PRIMARY KEY,
    pg_event_id  VARCHAR(100) NOT NULL,
    payment_id   UUID,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT uk_webhook_pg_event UNIQUE (pg_event_id)
);

-- 후속 소비자 중복 소비 방지: 부수효과와 같은 트랜잭션에서 insert
--  소비자 그룹이 여러 개일 수 있으므로 (consumer_group, event_id)를 키로 둔다
CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (consumer_group, event_id)
);
