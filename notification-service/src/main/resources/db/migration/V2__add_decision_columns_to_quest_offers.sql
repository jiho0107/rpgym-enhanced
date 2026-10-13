-- Slack 버튼 클릭을 즉시 접수(200)하고 game-service 반영은 스케줄러가 나중에 처리(재시도 포함)하기 위한 컬럼들.
-- 클릭 요청의 payload는 접수 직후 사라지므로, 나중에 처리하고 카드를 갱신하는 데 필요한 값을 여기에 남긴다.
ALTER TABLE notification_service.quest_offers
    ADD COLUMN title              VARCHAR(100),
    ADD COLUMN requested_action   VARCHAR(20),
    ADD COLUMN clicked_channel_id VARCHAR(100),
    ADD COLUMN clicked_message_ts VARCHAR(30),
    ADD COLUMN response_url       TEXT,
    ADD COLUMN attempt_count      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at    TIMESTAMPTZ,
    ADD COLUMN close_reason       VARCHAR(30);

-- 기존 행은 이미 처리가 끝난 이력이라 정확한 제목이 필요 없다. 기본 문구로 채운 뒤 NOT NULL로 전환한다.
UPDATE notification_service.quest_offers
SET title = '오늘의 퀘스트'
WHERE title IS NULL;

ALTER TABLE notification_service.quest_offers
    ALTER COLUMN title SET NOT NULL;

-- 스케줄러가 1초마다 처리할 행(PROCESSING, next_attempt_at <= now)을 찾는다.
-- PROCESSING 행은 처리 중인 몇 건뿐이라 부분 인덱스로 두면 테이블이 커져도 인덱스는 작게 유지된다.
CREATE INDEX ix_quest_offers_processing_next_attempt_at
    ON notification_service.quest_offers (next_attempt_at)
    WHERE status = 'PROCESSING';
