package com.workoutdone.rpgym.notification.partyquest.adapter.out.slack;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.Slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Slack 으로 파티 퀘스트 카드가 잘 도착하는지 눈으로 확인하려고 넣어둔 수동 스모크 테스트다.
 * 나중에 카드 문구/형식을 바꾸거나 Slack 연동을 점검할 때 다시 돌려 보라고 남겨 두었다.
 *
 * 환경변수 SLACK_BOT_TOKEN(봇 토큰)과 SLACK_TEST_USER_ID(받을 사람의 Slack 사용자 ID)가
 * 모두 있을 때만 실행되고, 없으면 건너뛰므로 CI 나 일반 테스트 실행에는 영향이 없다.
 *
 * 실행 예시 (PowerShell):
 *   $env:SLACK_BOT_TOKEN = "xoxb-..."
 *   $env:SLACK_TEST_USER_ID = "U..."
 *   ./gradlew :notification-service:test --tests "*PartyQuestSlackNotifierSmokeTest" --rerun-tasks
 */
@EnabledIfEnvironmentVariable(named = "SLACK_BOT_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLACK_TEST_USER_ID", matches = ".+")
class PartyQuestSlackNotifierSmokeTest {

    @Test
    @DisplayName("[수동] 실제 Slack DM 으로 파티 퀘스트 생성 카드를 발송한다")
    void sendsRealPartyQuestCard() {
        String botToken = System.getenv("SLACK_BOT_TOKEN");
        String slackUserId = System.getenv("SLACK_TEST_USER_ID");

        SlackApiClient slackApiClient = new SlackApiClient(Slack.getInstance().methods(botToken));
        PartyQuestSlackNotifier notifier = new PartyQuestSlackNotifier(slackApiClient, new ObjectMapper());

        PartyQuestCreatedData data = new PartyQuestCreatedData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "[테스트] 오늘 다 같이 만보 걷기", "STEPS", 40000, 100,
                Instant.parse("2026-09-24T14:59:59Z"),
                List.of(new PartyQuestCreatedData.Member(UUID.randomUUID())));

        SlackMessageResult result = notifier.sendCreated(slackUserId, data);

        assertThat(result.channel()).isNotBlank();
        assertThat(result.ts()).isNotBlank();
    }
}
