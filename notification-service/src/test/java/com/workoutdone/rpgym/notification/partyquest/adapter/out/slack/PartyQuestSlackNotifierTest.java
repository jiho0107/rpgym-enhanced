package com.workoutdone.rpgym.notification.partyquest.adapter.out.slack;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyQuestSlackNotifierTest {

    private static final String SLACK_ID = "U0123456789";
    private static final SlackMessageResult RESULT = new SlackMessageResult("D0123456789", "1732147200.000100");

    @Mock
    private SlackApiClient slackApiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PartyQuestSlackNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new PartyQuestSlackNotifier(slackApiClient, objectMapper);
    }

    private PartyQuestCreatedData data(String title, String metric, Instant expiredAt) {
        return new PartyQuestCreatedData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                title, metric, 40000, 100, expiredAt,
                List.of(new PartyQuestCreatedData.Member(UUID.randomUUID())));
    }

    private JsonNode sendAndCaptureBlocks(PartyQuestCreatedData data) throws Exception {
        when(slackApiClient.postMessage(eq(SLACK_ID), any(), any())).thenReturn(RESULT);

        SlackMessageResult result = notifier.sendCreated(SLACK_ID, data);
        assertThat(result).isEqualTo(RESULT);

        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackApiClient).postMessage(eq(SLACK_ID), blocksCaptor.capture(), any());
        return objectMapper.readTree(blocksCaptor.getValue());
    }

    @Test
    @DisplayName("sendCreated — 제목, 지표, 목표, 보상 XP, 마감 시각이 담긴 안내 카드를 slackId 로 발송한다")
    void buildsCardWithQuestDetails() throws Exception {
        // 2026-09-24T14:59:59Z = KST 23:59:59
        JsonNode blocks = sendAndCaptureBlocks(
                data("오늘 다 같이 만보 걷기", "STEPS", Instant.parse("2026-09-24T14:59:59Z")));

        assertThat(blocks.size()).isEqualTo(3);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("section");
        assertThat(blocks.get(0).get("text").get("text").asText()).contains("파티 퀘스트가 시작됐어요");
        assertThat(blocks.get(1).get("text").get("text").asText()).isEqualTo("*오늘 다 같이 만보 걷기*");

        String detail = blocks.get(2).get("text").get("text").asText();
        assertThat(detail)
                .contains("지표: 걸음 수")
                .contains("파티 목표: 40000")
                .contains("100 XP")
                .contains("마감: 오늘 23:59");
    }

    @Test
    @DisplayName("sendCreated — 버튼(actions 블록) 없는 안내형 카드이고, 대체 텍스트가 함께 전달된다")
    void cardHasNoButtonsAndHasFallbackText() throws Exception {
        when(slackApiClient.postMessage(eq(SLACK_ID), any(), any())).thenReturn(RESULT);

        notifier.sendCreated(SLACK_ID, data("제목", "STEPS", Instant.parse("2026-09-24T14:59:59Z")));

        ArgumentCaptor<String> blocksCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fallbackCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackApiClient).postMessage(eq(SLACK_ID), blocksCaptor.capture(), fallbackCaptor.capture());

        assertThat(blocksCaptor.getValue()).doesNotContain("\"actions\"").doesNotContain("\"button\"");
        assertThat(fallbackCaptor.getValue()).isEqualTo("파티 퀘스트가 시작됐어요");
    }

    @Test
    @DisplayName("지표 코드는 사람이 읽는 말로 바뀌고, 모르는 값은 그대로, null 은 '-' 로 보인다")
    void metricLabels() throws Exception {
        assertThat(detailOf(data("t", "ACTIVE_MINUTES", null))).contains("지표: 활동 시간(분)");
        assertThat(detailOf(data("t", "ACTIVE_CALORIES", null))).contains("지표: 활동 칼로리");
        assertThat(detailOf(data("t", "SOMETHING_NEW", null))).contains("지표: SOMETHING_NEW");
        assertThat(detailOf(data("t", null, null))).contains("지표: -");
    }

    @Test
    @DisplayName("expiredAt 이 없으면 마감 문구를 넣지 않는다")
    void omitsDeadlineWhenExpiredAtIsNull() throws Exception {
        assertThat(detailOf(data("제목", "STEPS", null))).doesNotContain("마감");
    }

    @Test
    @DisplayName("제목의 & < > 는 Slack 제어 문자로 해석되지 않게 이스케이프된다")
    void escapesTitle() throws Exception {
        JsonNode blocks = sendAndCaptureBlocks(data("A & B <@channel>", "STEPS", null));

        assertThat(blocks.get(1).get("text").get("text").asText())
                .isEqualTo("*A &amp; B &lt;@channel&gt;*");
    }

    private String detailOf(PartyQuestCreatedData data) throws Exception {
        org.mockito.Mockito.clearInvocations(slackApiClient);
        return sendAndCaptureBlocks(data).get(2).get("text").get("text").asText();
    }
}
