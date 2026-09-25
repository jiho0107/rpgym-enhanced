package com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka;

import com.workoutdone.rpgym.notification.questoffer.FakeQuestOfferRepository;
import com.workoutdone.rpgym.notification.partyquest.application.PartyQuestCreatedHandler;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameEventConsumerTest와 달리 QuestOfferService를 mock으로 대체하지 않고
 * FakeQuestOfferRepository와 함께 실제로 동작시킨다 -- "같은 이벤트가 중복 소비돼도
 * Slack은 한 번만 불린다"는 멱등성은 여러 번의 consume() 호출에 걸쳐 상태가 실제로
 * 이어져야만 검증되는데, prepareForSend를 mock으로 미리 정해두면 그 상태 전이 자체를
 * 재현할 수 없기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class GameEventConsumerIdempotencyTest {

    private static final UUID USER_ID = UUID.fromString("9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33");
    private static final UUID SUGGESTION_ID = UUID.fromString("7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61");
    private static final String SLACK_ID = "U0123456789";

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private QuestOfferSlackNotifier questOfferSlackNotifier;

    private FakeQuestOfferRepository questOfferRepository;
    private GameEventConsumer consumer;

    @BeforeEach
    void setUp() {
        questOfferRepository = new FakeQuestOfferRepository();
        QuestOfferService questOfferService = new QuestOfferService(questOfferRepository);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        consumer = new GameEventConsumer(
                objectMapper, questOfferService, userServiceClient, questOfferSlackNotifier, new RetryTemplate(),
                mock(PartyQuestCreatedHandler.class));
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

    @Test
    @DisplayName("같은 QUEST_SUGGESTED 이벤트를 두 번 consume()해도 Slack은 한 번만 불리고 quest_offers 행도 하나만 SENT로 확정된다")
    void duplicateConsumptionSendsSlackOnlyOnce() {
        when(userServiceClient.getUserInfo(USER_ID))
                .thenReturn(new UserInfoResponse(USER_ID, "지호", "USER", "ACTIVE", SLACK_ID));
        when(questOfferSlackNotifier.sendOffer(eq(SLACK_ID), any()))
                .thenReturn(new SlackMessageResult("D0123456789", "1732147200.000100"));

        // Kafka 리밸런싱/오프셋 커밋 지연 등으로 같은 레코드가 두 번 배달된 상황을 흉내낸다.
        consumer.consume(questSuggestedEvent());
        consumer.consume(questSuggestedEvent());

        verify(questOfferSlackNotifier, times(1)).sendOffer(eq(SLACK_ID), any());

        List<QuestOffer> stored = questOfferRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getSuggestionId()).isEqualTo(SUGGESTION_ID);
        assertThat(stored.get(0).getStatus()).isEqualTo(QuestOfferStatus.SENT);
    }

    @Test
    @DisplayName("1차 시도에서 Slack 발송이 실패해 PENDING으로 남아도, 재전송된 이벤트는 같은 행을 재사용해 정확히 한 번 더 성공한다")
    void retryAfterFailureReusesSameRowAndEventuallySucceeds() {
        when(userServiceClient.getUserInfo(USER_ID))
                .thenReturn(new UserInfoResponse(USER_ID, "지호", "USER", "ACTIVE", SLACK_ID));
        when(questOfferSlackNotifier.sendOffer(eq(SLACK_ID), any()))
                .thenThrow(new SlackMessageSendException("일시 장애"))
                .thenReturn(new SlackMessageResult("D0123456789", "1732147200.000100"));

        // 1차 시도: Slack 실패 -> 예외가 그대로 전파되고, 행은 PENDING인 채로 남는다.
        assertThatThrownBy(() -> consumer.consume(questSuggestedEvent()))
                .isInstanceOf(SlackMessageSendException.class);

        List<QuestOffer> afterFirstAttempt = questOfferRepository.findAll();
        assertThat(afterFirstAttempt).hasSize(1);
        assertThat(afterFirstAttempt.get(0).getStatus()).isEqualTo(QuestOfferStatus.PENDING);
        UUID offerId = afterFirstAttempt.get(0).getId();

        // 2차 시도(Kafka 재전송): 새 행을 만들지 않고 기존 PENDING 행을 재사용한다.
        consumer.consume(questSuggestedEvent());

        verify(questOfferSlackNotifier, times(2)).sendOffer(eq(SLACK_ID), any());
        List<QuestOffer> afterRetry = questOfferRepository.findAll();
        assertThat(afterRetry).hasSize(1);
        assertThat(afterRetry.get(0).getId()).isEqualTo(offerId);
        assertThat(afterRetry.get(0).getStatus()).isEqualTo(QuestOfferStatus.SENT);
    }
}
