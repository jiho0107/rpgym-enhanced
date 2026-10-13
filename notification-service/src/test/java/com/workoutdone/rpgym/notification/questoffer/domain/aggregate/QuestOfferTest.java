package com.workoutdone.rpgym.notification.questoffer.domain.aggregate;

import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

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
}
