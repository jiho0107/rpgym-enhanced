package com.workoutdone.rpgym.notification.partyquest.adapter.out.slack;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 파티 퀘스트 시작 안내 Block Kit 카드를 조립해서 SlackApiClient로 발송만 한다.
 *
 * 버튼이 없는 안내형 카드다. 파티장을 포함한 모든 파티원이 같은 카드를 받는다.
 * channel에는 사용자의 slackId를 그대로 넣는다 -- Slack이 DM을 자동으로 연다.
 */
@Component
@RequiredArgsConstructor
public class PartyQuestSlackNotifier {

    private static final String FALLBACK_TEXT = "파티 퀘스트가 시작됐어요"; //카드를 못 그리는 환경(푸시 알림 미리보기 등)에서 대신 보이는 문구
    //KST, DEADLINE_FORMAT은 마감 시각을 한국 시간 HH:mm으로 보여주기 위함
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final SlackApiClient slackApiClient;
    private final ObjectMapper objectMapper;

    //슬랙으로 메세지를 보내고 발송한 뒤 결과(채널,ts)를 돌려줌
    public SlackMessageResult sendCreated(String slackId, PartyQuestCreatedData data) {
        return slackApiClient.postMessage(slackId, buildBlocksJson(data), FALLBACK_TEXT);
    }

    //카드 본문을 조립
    //섹션 3개 추가
    // 1."파티 퀘스트가 시작됐어요!" 2.굵은 제목 3.상세(지표, 파티 목표, 보상 XP, 마감 시각)
    private String buildBlocksJson(PartyQuestCreatedData data) {
        ArrayNode blocks = objectMapper.createArrayNode();

        addSection(blocks, ":dart: *파티 퀘스트가 시작됐어요!*");
        addSection(blocks, "*" + escape(data.title()) + "*");

        String detail = "지표: " + metricLabel(data.metric())
                + "\n파티 목표: " + data.targetValue()
                + "\n보상: 완료 시 멤버 각자 " + data.rewardXp() + " XP";
        if (data.expiredAt() != null) {
            detail += "\n마감: 오늘 " + DEADLINE_FORMAT.format(data.expiredAt().atZone(KST));
        }
        addSection(blocks, detail);

        return blocks.toString();
    }

    //카드에 텍스트 블록 하나 추가하는 메서드(section 블록)
    private void addSection(ArrayNode blocks, String mrkdwn) {
        ObjectNode section = blocks.addObject();
        section.put("type", "section");
        section.putObject("text")
                .put("type", "mrkdwn")
                .put("text", mrkdwn);
    }

    //지표 코드를 사람이 읽는 말로 바꾸는 메서드
    // ex. STEPS -> 걸음 수
    private String metricLabel(String metric) {
        if (metric == null) {
            return "-";
        }
        return switch (metric) {
            case "STEPS" -> "걸음 수";
            case "ACTIVE_MINUTES" -> "활동 시간(분)";
            case "ACTIVE_CALORIES" -> "활동 칼로리";
            default -> metric;
        };
    }

    // 사용자가 입력한 제목이 Slack mrkdwn 의 제어 문자(&, <, >)로 해석되지 않게 이스케이프하는 메서드
    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
