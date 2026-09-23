package com.workoutdone.rpgym.notification.questoffer.domain.aggregate;

import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestOfferTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID SUGGESTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("pending() — 발송 시도만 기록한다. Slack 관련 필드는 전부 비어 있다")
    void pendingCreatesOfferWithNoSlackFields() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID);

        assertThat(offer.getId()).isEqualTo(ID);
        assertThat(offer.getSuggestionId()).isEqualTo(SUGGESTION_ID);
        assertThat(offer.getUserId()).isEqualTo(USER_ID);
        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.PENDING);
        assertThat(offer.getSlackDmChannelId()).isNull();
        assertThat(offer.getSlackMessageTs()).isNull();
        assertThat(offer.getSentAt()).isNull();
        assertThat(offer.isPending()).isTrue();
    }

    @Test
    @DisplayName("markSent() — Slack 발송 결과를 기록하고 SENT로 전환한다")
    void markSentTransitionsToSent() {
        QuestOffer offer = QuestOffer.pending(ID, SUGGESTION_ID, USER_ID);
        Instant sentAt = Instant.parse("2026-09-21T00:00:00Z");

        offer.markSent("D0123456789", "1732147200.000100", sentAt);

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.SENT);
        assertThat(offer.getSlackDmChannelId()).isEqualTo("D0123456789");
        assertThat(offer.getSlackMessageTs()).isEqualTo("1732147200.000100");
        assertThat(offer.getSentAt()).isEqualTo(sentAt);
        assertThat(offer.isPending()).isFalse();
    }
}
