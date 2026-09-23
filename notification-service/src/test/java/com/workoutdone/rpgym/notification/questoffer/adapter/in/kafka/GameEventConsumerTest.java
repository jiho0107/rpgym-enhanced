package com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka;

import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;
import com.workoutdone.rpgym.notification.slack.SlackMessageSendException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * game.events 계약은 mvp/test-yujun 브랜치(QuestToNotification, HealthEventConsumer)에서
 * 확인된 실제 모양(eventType=QUEST_SUGGESTED, data.suggestionId/title/metric/targetValue/expiresAt)을 따른다.
 */
@ExtendWith(MockitoExtension.class)
class GameEventConsumerTest {

    private static final UUID USER_ID = UUID.fromString("9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33");
    private static final UUID SUGGESTION_ID = UUID.fromString("7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61");
    private static final UUID OFFER_ID = UUID.randomUUID();
    private static final String SLACK_ID = "U0123456789";
    private static final String SLACK_CHANNEL = "D0123456789";
    private static final String SLACK_TS = "1732147200.000100";

    @Mock
    private QuestOfferService questOfferService;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private QuestOfferSlackNotifier questOfferSlackNotifier;

    private GameEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new GameEventConsumer(
                objectMapper, questOfferService, userServiceClient, questOfferSlackNotifier, new RetryTemplate());
    }

    private String questSuggestedEvent() {
        return """
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "eventType": "QUEST_SUGGESTED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": {
                    "suggestionId": "7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61",
                    "title": "20분 산책하기",
                    "metric": "ACTIVE_MINUTES",
                    "targetValue": 20,
                    "expiresAt": "2026-09-21T06:30:00Z"
                  }
                }
                """;
    }

    private UserInfoResponse userInfoWithSlackId(String slackId) {
        return new UserInfoResponse(USER_ID, "지호", "USER", "ACTIVE", slackId);
    }

    @Test
    @DisplayName("QUEST_SUGGESTED — DB 준비 -> slackId 조회 -> Slack 발송 -> SENT 확정까지 순서대로 이어진다")
    void questSuggestedHappyPath() {
        when(questOfferService.prepareForSend(SUGGESTION_ID, USER_ID)).thenReturn(Optional.of(OFFER_ID));
        when(userServiceClient.getUserInfo(USER_ID)).thenReturn(userInfoWithSlackId(SLACK_ID));
        when(questOfferSlackNotifier.sendOffer(eq(SLACK_ID), any()))
                .thenReturn(new SlackMessageResult(SLACK_CHANNEL, SLACK_TS));

        consumer.consume(questSuggestedEvent());

        verify(questOfferService).prepareForSend(SUGGESTION_ID, USER_ID);
        verify(questOfferSlackNotifier).sendOffer(eq(SLACK_ID), any());
        verify(questOfferService).markSent(OFFER_ID, SLACK_CHANNEL, SLACK_TS);
    }

    @Test
    @DisplayName("QUEST_SUGGESTED가 아닌 이벤트 — 아무 협력자도 건드리지 않고 조용히 지나간다")
    void ignoresOtherEventTypes() {
        consumer.consume("""
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "eventType": "QUEST_CREATED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": {}
                }
                """);

        verifyNoInteractions(questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("깨진 JSON — 예외를 던지지 않는다. 몇 번을 다시 읽어도 같은 자리에서 깨진다")
    void malformedJsonDoesNotThrow() {
        assertThatCode(() -> consumer.consume("{ this is not json"))
                .doesNotThrowAnyException();

        verifyNoInteractions(questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("eventType 누락 — 예외 없이 건너뛴다")
    void missingEventTypeIsSkipped() {
        assertThatCode(() -> consumer.consume("""
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": {}
                }
                """)).doesNotThrowAnyException();

        verifyNoInteractions(questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("QUEST_SUGGESTED인데 suggestionId/title 누락 — 계약 위반으로 보고 건너뛴다")
    void missingRequiredDataFieldsIsSkipped() {
        assertThatCode(() -> consumer.consume("""
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "eventType": "QUEST_SUGGESTED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": { "metric": "ACTIVE_MINUTES", "targetValue": 20 }
                }
                """)).doesNotThrowAnyException();

        verifyNoInteractions(questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("이미 처리된 제안(prepareForSend가 empty 반환) — user-service/Slack을 부르지 않는다")
    void alreadyProcessedOfferSkipsSlackSend() {
        when(questOfferService.prepareForSend(SUGGESTION_ID, USER_ID)).thenReturn(Optional.empty());

        consumer.consume(questSuggestedEvent());

        verifyNoInteractions(userServiceClient, questOfferSlackNotifier);
        verify(questOfferService, never()).markSent(any(), any(), any());
    }

    @Test
    @DisplayName("slackId가 없는 유저 — PENDING 기록은 남기되 Slack은 호출하지 않는다")
    void blankSlackIdSkipsSlackSend() {
        when(questOfferService.prepareForSend(SUGGESTION_ID, USER_ID)).thenReturn(Optional.of(OFFER_ID));
        when(userServiceClient.getUserInfo(USER_ID)).thenReturn(userInfoWithSlackId(""));

        consumer.consume(questSuggestedEvent());

        verifyNoInteractions(questOfferSlackNotifier);
        verify(questOfferService, never()).markSent(any(), any(), any());
    }

    @Test
    @DisplayName("Slack 발송 실패 — 예외가 그대로 전파되어 Kafka가 메시지 전체를 재시도한다")
    void slackFailurePropagatesException() {
        when(questOfferService.prepareForSend(SUGGESTION_ID, USER_ID)).thenReturn(Optional.of(OFFER_ID));
        when(userServiceClient.getUserInfo(USER_ID)).thenReturn(userInfoWithSlackId(SLACK_ID));
        when(questOfferSlackNotifier.sendOffer(eq(SLACK_ID), any()))
                .thenThrow(new SlackMessageSendException("Slack 발송 실패. error=timeout"));

        assertThatThrownBy(() -> consumer.consume(questSuggestedEvent()))
                .isInstanceOf(SlackMessageSendException.class);

        verify(questOfferService, never()).markSent(any(), any(), any());
    }

    @Test
    @DisplayName("markSent이 일시적으로 실패해도 로컬 재시도로 결국 성공한다")
    void markSentTransientFailureIsRetried() {
        when(questOfferService.prepareForSend(SUGGESTION_ID, USER_ID)).thenReturn(Optional.of(OFFER_ID));
        when(userServiceClient.getUserInfo(USER_ID)).thenReturn(userInfoWithSlackId(SLACK_ID));
        when(questOfferSlackNotifier.sendOffer(eq(SLACK_ID), any()))
                .thenReturn(new SlackMessageResult(SLACK_CHANNEL, SLACK_TS));
        doThrow(new RuntimeException("DB 순간 장애"))
                .doNothing()
                .when(questOfferService).markSent(OFFER_ID, SLACK_CHANNEL, SLACK_TS);

        assertThatCode(() -> consumer.consume(questSuggestedEvent())).doesNotThrowAnyException();

        verify(questOfferService, times(2)).markSent(OFFER_ID, SLACK_CHANNEL, SLACK_TS);
    }
}
