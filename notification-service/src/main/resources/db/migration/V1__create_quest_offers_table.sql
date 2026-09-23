CREATE SCHEMA IF NOT EXISTS notification_service;

CREATE TABLE notification_service.quest_offers
(
    id                  UUID PRIMARY KEY,
    suggestion_id       UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    slack_dm_channel_id VARCHAR(100),
    slack_message_ts    VARCHAR(30),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sent_at             TIMESTAMPTZ,
    responded_at        TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- suggestionId 하나당 발송은 하나뿐이다. game-service가 재전송해도 이 제약이 중복 INSERT를 막는다.
CREATE UNIQUE INDEX ux_quest_offers_suggestion_id
    ON notification_service.quest_offers (suggestion_id);
