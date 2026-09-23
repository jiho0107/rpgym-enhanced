package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto.QuestSuggestedData;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestOfferSlackNotifierTest {

    private static final UUID SUGGESTION_ID = UUID.fromString("7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61");
    private static final String SLACK_ID = "U0123456789";

    @Mock
    private SlackApiClient slackApiClient;

    private QuestOfferSlackNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new QuestOfferSlackNotifier(slackApiClient, new ObjectMapper());
    }

    @Test
    @DisplayName("sendOffer — 제목과 수락/거절 버튼(value=suggestionId)이 담긴 Block Kit을 만들어 발송한다")
    void sendOfferBuildsBlockKitCard() throws Exception {
        SlackMessageResult expected = new SlackMessageResult("D0123456789", "1732147200.000100");
        when(slackApiClient.postMessage(eq(SLACK_ID), any(), any())).thenReturn(expected);

        QuestSuggestedData data = new QuestSuggestedData(
                SUGGESTION_ID, "20분 산책하기", "ACTIVE_MINUTES", 20,
                Instant.parse("2026-09-21T06:30:00Z"));

        SlackMessageResult result = notifier.sendOffer(SLACK_ID, data);

        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<String> blocksJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fallbackTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackApiClient).postMessage(eq(SLACK_ID), blocksJsonCaptor.capture(), fallbackTextCaptor.capture());

        JsonNode blocks = new ObjectMapper().readTree(blocksJsonCaptor.getValue());

        assertThat(blocks.get(0).get("type").asText()).isEqualTo("section");
        assertThat(blocks.get(0).get("text").get("text").asText()).isEqualTo("*20분 산책하기*");
        // 보상(XP) 정보는 이 시점엔 없는 값이라 카드에 나타나면 안 된다.
        assertThat(blocks.get(0).get("text").get("text").asText()).doesNotContain("XP");

        JsonNode elements = blocks.get(1).get("elements");
        assertThat(elements.get(0).get("action_id").asText()).isEqualTo("quest_offer_accept");
        assertThat(elements.get(0).get("value").asText()).isEqualTo(SUGGESTION_ID.toString());
        assertThat(elements.get(1).get("action_id").asText()).isEqualTo("quest_offer_reject");
        assertThat(elements.get(1).get("value").asText()).isEqualTo(SUGGESTION_ID.toString());

        assertThat(fallbackTextCaptor.getValue()).isEqualTo("오늘의 Quest 제안이 도착했어요");
    }
}
