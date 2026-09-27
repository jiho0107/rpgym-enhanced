CREATE SCHEMA IF NOT EXISTS game_service;

-- ============================================================
-- quests
-- ============================================================
CREATE TABLE game_service.quests (
    quest_id                 UUID         NOT NULL,
    user_id                  UUID         NOT NULL,
    suggestion_id            UUID         NOT NULL,
    title                    VARCHAR(100) NOT NULL,
    metric                   VARCHAR(20)  NOT NULL,
    target_val               INTEGER      NOT NULL,
    baseline_val             INTEGER      NOT NULL,
    baseline_measured_at     TIMESTAMPTZ  NOT NULL,
    last_applied_measured_at TIMESTAMPTZ,
    last_cumulative_val      INTEGER,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    reward_xp                INTEGER      NOT NULL,
    expired_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_quests PRIMARY KEY (quest_id),
    CONSTRAINT ck_quests_target_val   CHECK (target_val > 0),
    CONSTRAINT ck_quests_baseline_val CHECK (baseline_val >= 0),
    CONSTRAINT ck_quests_expired_at   CHECK (expired_at > baseline_measured_at),
    CONSTRAINT ck_quests_metric CHECK (metric IN ('STEPS', 'ACTIVE_MINUTES', 'ACTIVE_CALORIES')),
    CONSTRAINT ck_quests_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_quests_suggestion
    ON game_service.quests (suggestion_id);

CREATE INDEX idx_quests_user_status
    ON game_service.quests (user_id, status, expired_at);

-- ============================================================
-- xp_ledgers
-- ============================================================
CREATE TABLE game_service.xp_ledgers (
    ledger_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_id   UUID        NOT NULL,
    amount      INTEGER     NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_xp_ledgers PRIMARY KEY (ledger_id),
    CONSTRAINT ck_xp_ledgers_amount CHECK (amount > 0),
    CONSTRAINT ck_xp_ledgers_source_type CHECK (source_type IN ('QUEST'))
);

-- 중복 지급이 절대 없도록 막음

-- 동작: DB에 user_id, source_type, source_id 조합이 동일한 행이 존재하면
-- 두번째 insert 시점에 DB가 에러를 터뜨리며 트랜잭션 롤백 flag를 세움

-- 부가효과: 유니크 제약 조건을 검사하려면 해당 컬럼을 빨리 찾아야 하므로 b-tree 색인
-- 이 내부에 자동으로 생성

-- 요약하자면 이 유저(userid)가 같은 퀘스트/파티퀘스트 보상을 두번 받는 일이 없도록
-- DB단에서 막는다
CREATE UNIQUE INDEX uk_xp_ledgers_source
    ON game_service.xp_ledgers (user_id, source_type, source_id);

-- 특정 조회 쿼리의 검색/정렬 속도 향상

-- 동작: 동일한 userid와 occured_at조합이 100개 들어와도 아무런 제약없이 저장
-- 다만 데이터를 미리 user_id 기준 + occuredAt 최신순(DESC)로 정렬된 B-tree로 만듦

-- 요약하자면 유저가 자기 xp 내역 볼때 테이블 전체를 뒤지지 말고 최신순으로 빠르게 찾아라
CREATE INDEX idx_xp_ledgers_user
    ON game_service.xp_ledgers (user_id, occurred_at DESC);

-- ============================================================
-- wallets
-- ============================================================
CREATE TABLE game_service.wallets (
    user_id    UUID      NOT NULL,
    xp         INTEGER   NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_wallets PRIMARY KEY (user_id),
    CONSTRAINT ck_wallets_xp CHECK (xp >= 0)
);

-- ============================================================
-- outbox_events
-- ============================================================
CREATE TABLE game_service.outbox_events (
    outbox_id      UUID        NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    event_id       UUID        NOT NULL,
    partition_key  VARCHAR(50) NOT NULL,
    payload        JSONB       NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count    INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMPTZ,

    CONSTRAINT pk_outbox_events PRIMARY KEY (outbox_id),
    CONSTRAINT ck_outbox_events_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_outbox_events_aggregate_type CHECK (aggregate_type IN ('QUEST')),
    CONSTRAINT ck_outbox_events_event_type CHECK (event_type IN ('QUEST_CREATED', 'QUEST_COMPLETED')),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE UNIQUE INDEX uk_outbox_events_event_id
    ON game_service.outbox_events (event_id);

CREATE UNIQUE INDEX uk_outbox_events_aggregate
    ON game_service.outbox_events (aggregate_type, aggregate_id, event_type);

CREATE INDEX idx_outbox_events_status
    ON game_service.outbox_events (status, created_at);

-- ============================================================
-- user_latest_snapshots
-- ============================================================
CREATE TABLE game_service.user_latest_snapshots (
    user_id         UUID        NOT NULL,
    activity_date   DATE        NOT NULL,
    measured_at     TIMESTAMPTZ NOT NULL,
    steps           INTEGER     NOT NULL,
    active_minutes  INTEGER     NOT NULL,
    active_calories INTEGER     NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_latest_snapshots PRIMARY KEY (user_id),
    CONSTRAINT ck_user_latest_snapshots_steps           CHECK (steps >= 0),
    CONSTRAINT ck_user_latest_snapshots_active_minutes  CHECK (active_minutes >= 0),
    CONSTRAINT ck_user_latest_snapshots_active_calories CHECK (active_calories >= 0)
);
