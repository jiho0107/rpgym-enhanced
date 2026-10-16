package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageUpdateException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 중복 클릭 카드 갱신의 수단 선택(chat.update -> response_url)만 검증한다.
 * 스프링 프록시 없이 직접 부르므로 여기서는 동기로 실행된다. 비동기 실행 자체는 QuestOfferCardUpdaterAsyncTest에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class QuestOfferCardUpdaterTest {

    private static final String CHANNEL = "D0123456789";
    private static final String TS = "1732147300.000300";
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T0123/456/abcdef";
    private static final QuestOfferCard CARD = new QuestOfferCard("[{\"type\":\"header\"}]", "ℹ️ 이 제안은 다른 메시지에서 처리 중이에요.");

    @Mock
    private SlackApiClient slackApiClient;

    private QuestOfferCardUpdater updater;

    @BeforeEach
    void setUp() {
        updater = new QuestOfferCardUpdater(slackApiClient);
    }

    @Test
    @DisplayName("1순위 — 이번 클릭의 channel/ts로 chat.update에 성공하면 response_url은 쓰지 않는다")
    void chatUpdateSucceeds() {
        updater.updateDuplicateClickCard(CHANNEL, TS, RESPONSE_URL, CARD);

        verify(slackApiClient).updateMessage(CHANNEL, TS, CARD.blocksJson(), CARD.fallbackText());
        verify(slackApiClient, never()).respond(any(), any(), any());
    }

    @Test
    @DisplayName("2순위 — chat.update가 실패하면 이번 클릭의 response_url로 같은 카드를 보낸다")
    void fallsBackToResponseUrlWhenChatUpdateFails() {
        doThrow(new SlackMessageUpdateException("message_not_found"))
                .when(slackApiClient).updateMessage(any(), any(), any(), any());

        updater.updateDuplicateClickCard(CHANNEL, TS, RESPONSE_URL, CARD);

        verify(slackApiClient).respond(RESPONSE_URL, CARD.blocksJson(), CARD.fallbackText());
    }

    @Test
    @DisplayName("payload에 카드 위치가 없으면 chat.update를 건너뛰고 바로 response_url을 쓴다")
    void usesResponseUrlWhenClickedPositionMissing() {
        updater.updateDuplicateClickCard(null, null, RESPONSE_URL, CARD);

        verify(slackApiClient, never()).updateMessage(any(), any(), any(), any());
        verify(slackApiClient).respond(RESPONSE_URL, CARD.blocksJson(), CARD.fallbackText());
    }

    @Test
    @DisplayName("두 수단이 모두 실패해도 예외를 던지지 않는다 (표시만 맞추는 작업이라 처리 결과에 영향이 없다)")
    void bothFailWithoutThrowing() {
        doThrow(new SlackMessageUpdateException("message_not_found"))
                .when(slackApiClient).updateMessage(any(), any(), any(), any());
        doThrow(new SlackMessageUpdateException("expired_url"))
                .when(slackApiClient).respond(any(), any(), any());

        assertThatCode(() -> updater.updateDuplicateClickCard(CHANNEL, TS, RESPONSE_URL, CARD))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("chat.update가 실패하고 response_url도 없으면 조용히 끝난다")
    void chatUpdateFailsAndNoResponseUrl() {
        doThrow(new SlackMessageUpdateException("message_not_found"))
                .when(slackApiClient).updateMessage(any(), any(), any(), any());

        assertThatCode(() -> updater.updateDuplicateClickCard(CHANNEL, TS, null, CARD))
                .doesNotThrowAnyException();
        verify(slackApiClient, never()).respond(any(), any(), any());
    }
}
