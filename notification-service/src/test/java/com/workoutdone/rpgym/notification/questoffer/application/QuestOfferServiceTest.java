package com.workoutdone.rpgym.notification.questoffer.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
import com.workoutdone.rpgym.notification.questoffer.domain.repo.QuestOfferRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestOfferServiceTest {

    private static final UUID SUGGESTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private QuestOfferRepository questOfferRepository;

    private QuestOfferService service;

    @BeforeEach
    void setUp() {
        service = new QuestOfferService(questOfferRepository);
    }

    @Test
    @DisplayName("prepareForSend — 처음 보는 suggestionId면 PENDING으로 새로 저장하고 그 id를 반환한다")
    void prepareForSendCreatesNewPendingOffer() {
        when(questOfferRepository.findBySuggestionId(SUGGESTION_ID)).thenReturn(Optional.empty());
        when(questOfferRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        Optional<UUID> offerId = service.prepareForSend(SUGGESTION_ID, USER_ID);

        ArgumentCaptor<QuestOffer> captor = ArgumentCaptor.forClass(QuestOffer.class);
        verify(questOfferRepository).save(captor.capture());

        QuestOffer saved = captor.getValue();
        assertThat(offerId).contains(saved.getId());
        assertThat(saved.getSuggestionId()).isEqualTo(SUGGESTION_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getStatus()).isEqualTo(QuestOfferStatus.PENDING);
    }

    @Test
    @DisplayName("prepareForSend — 기존 행이 PENDING이면 그 id를 재사용한다 (직전 Slack 발송 실패 후 재시도)")
    void prepareForSendReusesExistingPendingOffer() {
        QuestOffer existing = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, USER_ID);
        when(questOfferRepository.findBySuggestionId(SUGGESTION_ID)).thenReturn(Optional.of(existing));

        Optional<UUID> offerId = service.prepareForSend(SUGGESTION_ID, USER_ID);

        assertThat(offerId).contains(existing.getId());
        verify(questOfferRepository, never()).save(any());
    }

    @Test
    @DisplayName("prepareForSend — 기존 행이 이미 SENT면 진짜 중복이므로 건너뛴다")
    void prepareForSendSkipsAlreadySentOffer() {
        QuestOffer existing = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, USER_ID);
        existing.markSent("D0123456789", "1732147200.000100", Instant.now());
        when(questOfferRepository.findBySuggestionId(SUGGESTION_ID)).thenReturn(Optional.of(existing));

        Optional<UUID> offerId = service.prepareForSend(SUGGESTION_ID, USER_ID);

        assertThat(offerId).isEmpty();
        verify(questOfferRepository, never()).save(any());
    }

    @Test
    @DisplayName("markSent — 대상 행을 찾아 Slack 발송 결과를 기록하고 SENT로 확정한다")
    void markSentUpdatesOffer() {
        QuestOffer offer = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, USER_ID);
        when(questOfferRepository.findById(offer.getId())).thenReturn(Optional.of(offer));

        service.markSent(offer.getId(), "D0123456789", "1732147200.000100");

        assertThat(offer.getStatus()).isEqualTo(QuestOfferStatus.SENT);
        assertThat(offer.getSlackDmChannelId()).isEqualTo("D0123456789");
        assertThat(offer.getSlackMessageTs()).isEqualTo("1732147200.000100");
    }

    @Test
    @DisplayName("markSent — 대상 행이 없으면 QUEST_OFFER_NOT_FOUND를 던진다")
    void markSentThrowsWhenOfferNotFound() {
        UUID offerId = UUID.randomUUID();
        when(questOfferRepository.findById(offerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSent(offerId, "D0123456789", "1732147200.000100"))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.QUEST_OFFER_NOT_FOUND);
    }
}
