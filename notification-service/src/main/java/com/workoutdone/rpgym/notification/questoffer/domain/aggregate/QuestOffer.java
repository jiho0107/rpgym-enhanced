package com.workoutdone.rpgym.notification.questoffer.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedEntity;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Slack으로 발송한 Quest 제안 하나당 한 행.
 *
 * game-service의 QuestSuggestion과 대칭되는 개념이지만 별개의 애그리거트다 --
 * 저긴 "수락하면 Quest가 되는 제안"을 관리하고, 여긴 "Slack 발송/응답 이력"만 관리한다.
 * 그래서 연관키는 questId가 아니라 suggestionId다: 이 행이 처음 만들어지는 시점(발송 전)엔 아직 Quest 자체가 존재하지 않는다.
 *
 * 상태 전이
 *   PENDING --markSent--> SENT
 *   PENDING/SENT --requestDecision--> PROCESSING   (Slack 클릭 접수)
 *   PROCESSING --complete--> ACCEPTED/REJECTED     (game-service 반영 성공)
 *   PROCESSING --close--> CLOSED                   (game-service가 확정적으로 거절)
 *   PROCESSING --revertToSent--> SENT              (재시도 소진, 사용자가 다시 누를 수 있게 되돌림)
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

    /** 카드를 상태별로 다시 그릴 때 쓴다. chat.update는 메시지 전체를 다시 보내야 해서 제목을 알아야 한다. */
    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_action", length = 20)
    private QuestOfferAction requestedAction;

    /**
     * 사용자가 실제로 누른 카드의 위치(클릭 payload의 container).
     * markSent가 실패한 뒤 Kafka 재시도로 카드가 두 장 나갈 수 있고, 그러면 slack_message_ts에는 마지막 카드의 ts만 남는다.
     * 누른 카드를 정확히 고치려면 클릭 시점의 값을 따로 남겨야 한다.
     */
    @Column(name = "clicked_channel_id", length = 100)
    private String clickedChannelId;

    @Column(name = "clicked_message_ts", length = 30)
    private String clickedMessageTs;

    /** chat.update 실패 시 보조 갱신 수단. Slack 제한: 30분 안에 최대 5회. */
    @Column(name = "response_url", columnDefinition = "TEXT")
    private String responseUrl;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 스케줄러가 이 행을 처리할 수 있는 시각. 선점 시에는 lease 만료 시각(현재 시각 + 30초)으로 쓴다. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private QuestOfferCloseReason closeReason;

    /**
     * 발송을 "시도했다"는 기록만 먼저 남긴다.
     * slack_dm_channel_id / slack_message_ts / sent_at은 Slack에 실제로 성공해야만 알 수 있는 값이라 전부 NULL로 시작
     */
    public static QuestOffer pending(UUID id, UUID suggestionId, UUID userId, String title) {
        QuestOffer offer = new QuestOffer();
        offer.id = id;
        offer.suggestionId = suggestionId;
        offer.userId = userId;
        offer.title = Objects.requireNonNull(title, "title");
        offer.status = QuestOfferStatus.PENDING;
        return offer;
    }

    /**
     * Slack 발송 성공을 기록한다.
     * "발송 시도 기록(PENDING INSERT)"과는 별도 트랜잭션에서 호출돼야 한다
     * -- Slack 호출을 그 사이에 두는 이유는 QuestOfferService 쪽 주석에 있다.
     *
     * PENDING일 때만 SENT로 바꾼다. Kafka 재시도의 markSent가 늦게 들어왔을 때 이미 클릭이 접수돼 있으면
     * (PROCESSING 이상) 상태를 SENT로 되돌리면 안 된다 -- 그 경우엔 비어 있는 발송 정보만 채운다.
     */
    public void markSent(String slackDmChannelId, String slackMessageTs, Instant sentAt) {
        if (status == QuestOfferStatus.PENDING) {
            this.status = QuestOfferStatus.SENT;
        } else if (this.slackMessageTs != null) {
            return;
        }
        this.slackDmChannelId = slackDmChannelId;
        this.slackMessageTs = slackMessageTs;
        this.sentAt = sentAt;
    }

    /**
     * Slack 버튼 클릭을 접수한다. 사용자가 카드를 눌렀다면 Slack 발송은 실제로 성공한 것이므로
     * markSent가 기록되지 못한 PENDING에서도 접수한다.
     * PENDING/SENT 일 때만 PROCESSING 으로 바꾸고 클릭 정보를 기록한 뒤 true 를 반환하고,
     * 그 밖의 상태(중복 클릭)는 아무것도 바꾸지 않고 false를 반환함.
     *
     * @return 접수했으면 true.
     *         이미 접수/처리된 제안이면(중복 클릭) 아무것도 바꾸지 않고 false
     *         -- 첫 클릭의 카드 위치를 덮어쓰면 처리 결과가 엉뚱한 카드에 표시된다.
     */
    public boolean requestDecision(QuestOfferAction action, String clickedChannelId, String clickedMessageTs,
                                   String responseUrl, Instant now) {
        if (status != QuestOfferStatus.PENDING && status != QuestOfferStatus.SENT) {
            return false;
        }
        this.status = QuestOfferStatus.PROCESSING;
        this.requestedAction = Objects.requireNonNull(action, "action");
        this.clickedChannelId = clickedChannelId;
        this.clickedMessageTs = clickedMessageTs;
        this.responseUrl = responseUrl;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.respondedAt = now;
        return true;
    }

    /**
     * 스케줄러가 이 행을 가져갈 때 호출한다.
     * lease (30초)동안은 다른 인스턴스가 다시 가져가지 않고,
     * 처리 도중 서버가 죽으면 lease가 끝난 뒤 다른 인스턴스가 이어서 처리한다.
     * 시도 횟수를 +1 하고, 다음 시도 시각을 현재 + lease(30초) 로 둔다.
     */
    public void claim(Instant now, Duration lease) {
        requireProcessing("claim");
        this.attemptCount++;
        this.nextAttemptAt = now.plus(lease);
    }

    /** game-service 호출이 일시적으로 실패해 다음 시도를 예약한다. */
    public void scheduleRetry(Instant nextAttemptAt) {
        requireProcessing("scheduleRetry");
        this.nextAttemptAt = nextAttemptAt;
    }

    /** game-service가 사용자가 요청한 수락/거절을 반영했다.
     *  requested_action에 따라 ACCEPTED 또는 REJECTED 로 확정함
     */
    public void complete() {
        requireProcessing("complete");
        this.status = requestedAction == QuestOfferAction.ACCEPT
                ? QuestOfferStatus.ACCEPTED
                : QuestOfferStatus.REJECTED;
        this.nextAttemptAt = null;
    }

    /** game-service가 처리를 확정적으로 거절했다(만료, 대체 등). 재시도해도 결과가 같다.
     *  status를 CLOSED 로 바꾸고 close_reason 을 기록함
     */
    public void close(QuestOfferCloseReason reason) {
        requireProcessing("close");
        this.status = QuestOfferStatus.CLOSED;
        this.closeReason = Objects.requireNonNull(reason, "reason");
        this.nextAttemptAt = null;
    }

    /**
     * 재시도를 다 써서 사용자가 다시 누를 수 있게 되돌린다.
     * 출발 상태와 상관없이 SENT로 되돌리고, 클릭 정보는 NULL로, 시도 횟수는 0으로 초기화함.
     * -- PENDING이면 Kafka 재시도가 카드를 또 보낼 수 있고, 사용자가 카드를 눌렀다는 건 발송이 이미 성공했다는 뜻이다.
     * respondedAt은 남겨두고 다음 클릭이 접수될 때 새 시각으로 갱신한다.
     */
    public void revertToSent() {
        requireProcessing("revertToSent");
        this.status = QuestOfferStatus.SENT;
        this.requestedAction = null;
        this.clickedChannelId = null;
        this.clickedMessageTs = null;
        this.responseUrl = null;
        this.attemptCount = 0;
        this.nextAttemptAt = null;
    }

    public boolean isPending() {
        return status == QuestOfferStatus.PENDING;
    }

    public boolean isProcessing() {
        return status == QuestOfferStatus.PROCESSING;
    }

    private void requireProcessing(String operation) {
        if (status != QuestOfferStatus.PROCESSING) {
            throw new IllegalStateException(
                    operation + "은 PROCESSING 상태에서만 가능하다. suggestionId=" + suggestionId + " status=" + status);
        }
    }
}
