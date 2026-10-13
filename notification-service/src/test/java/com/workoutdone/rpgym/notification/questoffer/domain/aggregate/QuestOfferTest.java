package com.workoutdone.rpgym.notification.questoffer.domain.aggregate;

import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestOfferTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID SUGGESTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TITLE = "20분 산책하기";

    private static final String DM_CHANNEL = "D0123456789";
    private static final String SENT_TS = "1732147200.000100";
    private static final String CLICKED_TS = "1732147100.000050";
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T0/1/abc";
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private static QuestOffer sentOffer() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);
        offer.markSent(DM_CHANNEL, SENT_TS, NOW);
        return offer;
    }

    private static QuestOffer processingOffer(QuestOfferAction action) {
        QuestOffer offer = sentOffer();
        offer.requestDecision(action, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW);
        return offer;
    }

    @Test
    @DisplayName("pending() — 발송 시도만 기록한다. Slack 관련 필드와 클릭 처리 필드는 전부 비어 있다")
    void pendingCreatesOfferWithNoSlackFields() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);

        assertThat(offer.getId()).isEqualTo(ID);
        assertThat(offer.getSuggestionId()).isEqualTo(SUGGESTION_ID);
        assertThat(offer.getUserId()).isEqualTo(USER_ID);
        assertThat(offer.getTitle()).isEqualTo(TITLE);
        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PENDING);
        assertThat(offer.getSlackDmChannelId()).isNull();
        assertThat(offer.getSlackMessageTs()).isNull();
        assertThat(offer.getSentAt()).isNull();
        assertThat(offer.getRequestedAction()).isNull();
        assertThat(offer.getAttemptCount()).isZero();
        assertThat(offer.getNextAttemptAt()).isNull();
        assertThat(offer.isPending()).isTrue();
    }

    @Test
    @DisplayName("pending() — title이 없으면 만들 수 없다 (카드를 다시 그릴 수 없다)")
    void pendingRequiresTitle() {
        assertThatThrownBy(() -> QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("markSent() — PENDING이면 Slack 발송 결과를 기록하고 SENT로 전환한다")
    void markSentTransitionsToSent() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);

        offer.markSent(DM_CHANNEL, SENT_TS, NOW);

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.SENT);
        assertThat(offer.getSlackDmChannelId()).isEqualTo(DM_CHANNEL);
        assertThat(offer.getSlackMessageTs()).isEqualTo(SENT_TS);
        assertThat(offer.getSentAt()).isEqualTo(NOW);
        assertThat(offer.isPending()).isFalse();
    }

    @Test
    @DisplayName("markSent() — PENDING에서 클릭이 먼저 접수됐다면 상태는 그대로 두고 비어 있는 발송 정보만 채운다")
    void markSentAfterClickKeepsStatusAndFillsEmptySlackFields() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);
        offer.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW);

        offer.markSent(DM_CHANNEL, SENT_TS, NOW.plusSeconds(5));

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PROCESSING);
        assertThat(offer.getSlackMessageTs()).isEqualTo(SENT_TS);
        assertThat(offer.getSentAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("markSent() — 이미 최종 상태이고 발송 정보도 있으면 아무것도 바꾸지 않는다")
    void markSentOnFinishedOfferDoesNothing() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.claim(NOW, LEASE);
        offer.complete();

        offer.markSent("D9999999999", "1732149999.000999", NOW.plusSeconds(60));

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.ACCEPTED);
        assertThat(offer.getSlackDmChannelId()).isEqualTo(DM_CHANNEL);
        assertThat(offer.getSlackMessageTs()).isEqualTo(SENT_TS);
        assertThat(offer.getSentAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("requestDecision() — SENT에서 클릭을 접수하면 PROCESSING으로 전이하고 클릭 정보를 기록한다")
    void requestDecisionFromSent() {
        QuestOffer offer = sentOffer();

        boolean accepted = offer.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW);

        assertThat(accepted).isTrue();
        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PROCESSING);
        assertThat(offer.getRequestedAction()).isEqualTo(QuestOfferAction.ACCEPT);
        assertThat(offer.getClickedChannelId()).isEqualTo(DM_CHANNEL);
        assertThat(offer.getClickedMessageTs()).isEqualTo(CLICKED_TS);
        assertThat(offer.getResponseUrl()).isEqualTo(RESPONSE_URL);
        assertThat(offer.getAttemptCount()).isZero();
        assertThat(offer.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(offer.getRespondedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("requestDecision() — PENDING에서도 접수한다 (사용자가 눌렀다면 Slack 발송은 실제로 성공한 것)")
    void requestDecisionFromPending() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);

        boolean accepted = offer.requestDecision(QuestOfferAction.REJECT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW);

        assertThat(accepted).isTrue();
        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PROCESSING);
        assertThat(offer.getRequestedAction()).isEqualTo(QuestOfferAction.REJECT);
        assertThat(offer.getSlackMessageTs()).isNull();
        assertThat(offer.getClickedMessageTs()).isEqualTo(CLICKED_TS);
    }

    @Test
    @DisplayName("requestDecision() — 이미 PROCESSING이면 중복 클릭이다. 첫 클릭의 정보를 덮어쓰지 않고 false를 반환한다")
    void requestDecisionIgnoresDuplicateClick() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);

        boolean accepted = offer.requestDecision(
                QuestOfferAction.REJECT, DM_CHANNEL, "1732147300.000300", "https://hooks.slack.com/actions/T0/2/def",
                NOW.plusSeconds(10));

        assertThat(accepted).isFalse();
        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PROCESSING);
        assertThat(offer.getRequestedAction()).isEqualTo(QuestOfferAction.ACCEPT);
        assertThat(offer.getClickedMessageTs()).isEqualTo(CLICKED_TS);
        assertThat(offer.getResponseUrl()).isEqualTo(RESPONSE_URL);
        assertThat(offer.getRespondedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("requestDecision() — 최종 상태(ACCEPTED/REJECTED/CLOSED)에서도 중복 클릭으로 보고 false를 반환한다")
    void requestDecisionIgnoresClickOnFinishedOffer() {
        QuestOffer accepted = processingOffer(QuestOfferAction.ACCEPT);
        accepted.claim(NOW, LEASE);
        accepted.complete();

        QuestOffer closed = processingOffer(QuestOfferAction.ACCEPT);
        closed.claim(NOW, LEASE);
        closed.close(QuestOfferCloseReason.EXPIRED);

        assertThat(accepted.requestDecision(QuestOfferAction.REJECT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW))
                .isFalse();
        assertThat(closed.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW))
                .isFalse();
        assertThat(accepted.getStatus()).isEqualTo(QuestOfferStatus.ACCEPTED);
        assertThat(closed.getStatus()).isEqualTo(QuestOfferStatus.CLOSED);
    }

    @Test
    @DisplayName("claim() — 시도 횟수를 올리고 lease 만큼 다음 시도 시각을 미룬다")
    void claimIncrementsAttemptAndSetsLease() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);

        offer.claim(NOW, LEASE);

        assertThat(offer.getAttemptCount()).isEqualTo(1);
        assertThat(offer.getNextAttemptAt()).isEqualTo(NOW.plus(LEASE));
    }

    @Test
    @DisplayName("scheduleRetry() — 다음 시도 시각만 바꾸고 PROCESSING을 유지한다")
    void scheduleRetryKeepsProcessing() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.claim(NOW, LEASE);

        offer.scheduleRetry(NOW.plusSeconds(2));

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PROCESSING);
        assertThat(offer.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(offer.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("complete() — 요청한 행동에 따라 ACCEPTED 또는 REJECTED로 확정한다")
    void completeFollowsRequestedAction() {
        QuestOffer accept = processingOffer(QuestOfferAction.ACCEPT);
        accept.claim(NOW, LEASE);
        accept.complete();

        QuestOffer reject = processingOffer(QuestOfferAction.REJECT);
        reject.claim(NOW, LEASE);
        reject.complete();

        assertThat(accept.getStatus()).isEqualTo(QuestOfferStatus.ACCEPTED);
        assertThat(accept.getNextAttemptAt()).isNull();
        assertThat(reject.getStatus()).isEqualTo(QuestOfferStatus.REJECTED);
        assertThat(reject.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("close() — CLOSED로 확정하고 이유를 남긴다. 카드 문구 선택에 필요한 클릭 정보는 남겨둔다")
    void closeRecordsReason() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.claim(NOW, LEASE);

        offer.close(QuestOfferCloseReason.SUPERSEDED);

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.CLOSED);
        assertThat(offer.getCloseReason()).isEqualTo(QuestOfferCloseReason.SUPERSEDED);
        assertThat(offer.getNextAttemptAt()).isNull();
        assertThat(offer.getRequestedAction()).isEqualTo(QuestOfferAction.ACCEPT);
        assertThat(offer.getClickedMessageTs()).isEqualTo(CLICKED_TS);
    }

    @Test
    @DisplayName("revertToSent() — 재시도 소진 시 SENT로 되돌리고 클릭 처리 필드를 초기화한다")
    void revertToSentClearsDecisionFields() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.claim(NOW, LEASE);

        offer.revertToSent();

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.SENT);
        assertThat(offer.getRequestedAction()).isNull();
        assertThat(offer.getClickedChannelId()).isNull();
        assertThat(offer.getClickedMessageTs()).isNull();
        assertThat(offer.getResponseUrl()).isNull();
        assertThat(offer.getAttemptCount()).isZero();
        assertThat(offer.getNextAttemptAt()).isNull();
        assertThat(offer.getRespondedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("revertToSent() — PENDING에서 출발했어도 SENT로 되돌린다 (PENDING이면 Kafka 재시도가 카드를 또 보낸다)")
    void revertToSentFromPendingOriginGoesToSent() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID, TITLE);
        offer.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW);
        offer.claim(NOW, LEASE);

        offer.revertToSent();

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.SENT);
        assertThat(offer.isPending()).isFalse();
    }

    @Test
    @DisplayName("revertToSent() 후 다시 클릭하면 새 클릭으로 정상 접수된다")
    void clickAgainAfterRevertIsAccepted() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.claim(NOW, LEASE);
        offer.revertToSent();

        boolean accepted = offer.requestDecision(
                QuestOfferAction.REJECT, DM_CHANNEL, CLICKED_TS, RESPONSE_URL, NOW.plusSeconds(60));

        assertThat(accepted).isTrue();
        assertThat(offer.getRequestedAction()).isEqualTo(QuestOfferAction.REJECT);
        assertThat(offer.getRespondedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("PROCESSING이 아닐 때 처리 메서드를 부르면 IllegalStateException")
    void processingOnlyOperationsRejectOtherStatus() {
        QuestOffer offer = sentOffer();

        assertThatThrownBy(() -> offer.claim(NOW, LEASE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> offer.scheduleRetry(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(offer::complete).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> offer.close(QuestOfferCloseReason.EXPIRED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(offer::revertToSent).isInstanceOf(IllegalStateException.class);
    }
}
