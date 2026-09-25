package com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
import com.workoutdone.rpgym.notification.partyquest.application.PartyQuestCreatedHandler;
import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.GameEventConsumer;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** game.events 의 PARTY_QUEST_CREATED 가 GameEventConsumer 에서 PartyQuestCreatedHandler 로 올바르게 라우팅되는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class GameEventConsumerPartyQuestTest {

    private static final String PARTY_QUEST_ID = "3d9a1c47-5e02-4b8f-9a6d-7c1e2f084b55";
    private static final String OWNER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String MEMBER_ID = "22222222-2222-4222-8222-222222222222";

    @Mock
    private QuestOfferService questOfferService;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private QuestOfferSlackNotifier questOfferSlackNotifier;

    @Mock
    private PartyQuestCreatedHandler partyQuestCreatedHandler;

    private GameEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new GameEventConsumer(
                objectMapper, questOfferService, userServiceClient, questOfferSlackNotifier,
                new RetryTemplate(), partyQuestCreatedHandler);
    }

    private String envelope(String eventType, String dataJson) {
        return """
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "eventType": "%s",
                  "occurredAt": "2026-09-24T03:10:00Z",
                  "userId": "%s",
                  "data": %s
                }
                """.formatted(eventType, OWNER_ID, dataJson);
    }

    private String partyQuestCreatedData() {
        return """
                {
                  "partyQuestId": "%s",
                  "partyId": "9a8b7c6d-1111-4222-8333-444455556666",
                  "ownerId": "%s",
                  "title": "오늘 다 같이 만보 걷기",
                  "metric": "STEPS",
                  "targetValue": 40000,
                  "rewardXp": 100,
                  "expiredAt": "2026-09-24T14:59:59Z",
                  "unknownFutureField": "무시되어야 한다",
                  "members": [ {"userId": "%s"}, {"userId": "%s"} ]
                }
                """.formatted(PARTY_QUEST_ID, OWNER_ID, OWNER_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("PARTY_QUEST_CREATED — data 를 PartyQuestCreatedData 로 변환해 핸들러에 넘긴다 (모르는 필드는 무시)")
    void routesPartyQuestCreatedToHandler() {
        consumer.consume(envelope("PARTY_QUEST_CREATED", partyQuestCreatedData()));

        ArgumentCaptor<PartyQuestCreatedData> captor = ArgumentCaptor.forClass(PartyQuestCreatedData.class);
        verify(partyQuestCreatedHandler).handle(captor.capture());

        PartyQuestCreatedData data = captor.getValue();
        assertThat(data.partyQuestId()).isEqualTo(UUID.fromString(PARTY_QUEST_ID));
        assertThat(data.ownerId()).isEqualTo(UUID.fromString(OWNER_ID));
        assertThat(data.title()).isEqualTo("오늘 다 같이 만보 걷기");
        assertThat(data.metric()).isEqualTo("STEPS");
        assertThat(data.targetValue()).isEqualTo(40000);
        assertThat(data.rewardXp()).isEqualTo(100);
        assertThat(data.expiredAt()).isEqualTo(Instant.parse("2026-09-24T14:59:59Z"));
        assertThat(data.members()).extracting(PartyQuestCreatedData.Member::userId)
                .containsExactly(UUID.fromString(OWNER_ID), UUID.fromString(MEMBER_ID));

        // 일일 Quest 제안 경로는 건드리지 않는다.
        verifyNoInteractions(questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("PARTY_QUEST_CREATED 의 data 가 null 이거나 변환할 수 없으면 핸들러를 부르지 않고 예외 없이 끝난다")
    void skipsWhenDataIsNullOrMalformed() {
        assertThatCode(() -> {
            consumer.consume(envelope("PARTY_QUEST_CREATED", "null"));
            consumer.consume(envelope("PARTY_QUEST_CREATED",
                    "{\"partyQuestId\": \"" + PARTY_QUEST_ID + "\", \"targetValue\": \"숫자아님\"}"));
        }).doesNotThrowAnyException();

        verify(partyQuestCreatedHandler, never()).handle(any());
    }

    @Test
    @DisplayName("처리 대상이 아닌 eventType(QUEST_CREATED, PARTY_QUEST_COMPLETED 등)은 핸들러를 부르지 않고 건너뛴다")
    void ignoresOtherEventTypes() {
        assertThatCode(() -> {
            consumer.consume(envelope("QUEST_CREATED", "{}"));
            consumer.consume(envelope("PARTY_QUEST_COMPLETED", "{}"));
        }).doesNotThrowAnyException();

        verifyNoInteractions(partyQuestCreatedHandler, questOfferService, userServiceClient, questOfferSlackNotifier);
    }

    @Test
    @DisplayName("QUEST_SUGGESTED 는 기존처럼 일일 Quest 경로로 처리되고 파티 퀘스트 핸들러는 호출되지 않는다")
    void questSuggestedDoesNotReachPartyQuestHandler() {
        when(questOfferService.prepareForSend(any(), any())).thenReturn(Optional.empty());

        consumer.consume(envelope("QUEST_SUGGESTED", """
                {
                  "suggestionId": "7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61",
                  "title": "20분 산책하기",
                  "metric": "ACTIVE_MINUTES",
                  "targetValue": 20,
                  "expiresAt": "2026-09-24T06:30:00Z"
                }
                """));

        verify(questOfferService).prepareForSend(any(), any());
        verifyNoInteractions(partyQuestCreatedHandler);
    }
}
