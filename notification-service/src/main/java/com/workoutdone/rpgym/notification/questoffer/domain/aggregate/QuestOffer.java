package com.workoutdone.rpgym.notification.questoffer.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedEntity;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Slack으로 발송한 Quest 제안 하나당 한 행.
 *
 * game-service의 QuestSuggestion과 대칭되는 개념이지만 별개의 애그리거트다 --
 * 저긴 "수락하면 Quest가 되는 제안"을 관리하고, 여긴 "Slack 발송/응답 이력"만 관리한다.
 * 그래서 연관키는 questId가 아니라 suggestionId다: 이 행이 처음 만들어지는 시점(발송 전)엔 아직 Quest 자체가 존재하지 않는다.
 *
 */
@Entity
@Getter
@Table(name = "quest_offers", schema = "notification_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestOffer extends BaseCreatedEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "suggestion_id", nullable = false, updatable = false)
    private UUID suggestionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "slack_dm_channel_id", length = 100)
    private String slackDmChannelId;

    @Column(name = "slack_message_ts", length = 30)
    private String slackMessageTs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuestOfferStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /** 낙관적 락. Slack 응답 처리 중 중복 클릭/재시도가 같은 행을 동시에 건드릴 수 있다. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 발송을 "시도했다"는 기록만 먼저 남긴다.
     * slack_dm_channel_id / slack_message_ts / sent_at은 Slack에 실제로 성공해야만 알 수 있는 값이라 전부 NULL로 시작
     */
    public static QuestOffer pending(UUID id, UUID suggestionId, UUID userId) {
        QuestOffer offer = new QuestOffer();
        offer.id = id;
        offer.suggestionId = suggestionId;
        offer.userId = userId;
        offer.status = QuestOfferStatus.PENDING;
        return offer;
    }

    /**
     * Slack 발송 성공을 기록한다.
     * "발송 시도 기록(PENDING INSERT)"과는 별도 트랜잭션에서 호출돼야 한다
     * -- Slack 호출을 그 사이에 두는 이유는 QuestOfferService 쪽 주석에 있다.
     */
    public void markSent(String slackDmChannelId, String slackMessageTs, Instant sentAt) {
        this.slackDmChannelId = slackDmChannelId;
        this.slackMessageTs = slackMessageTs;
        this.status = QuestOfferStatus.SENT;
        this.sentAt = sentAt;
    }

    public boolean isPending() {
        return status == QuestOfferStatus.PENDING;
    }
}
