package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto.QuestSuggestedData;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Quest 제안 Block Kit 카드를 조립해서 SlackApiClient로 발송만 한다.(무엇을 보낼지만 결정)
 *
 * channel에는 DM 채널 ID가 아니라 사용자의 slackId를 그대로 넣는다 -- Slack이 DM을 자동으로 연다.
 * 버튼의 value에는 questId가 아니라 suggestionId를 담는다 -- 이 시점엔 questId가 없다.
 * 보상(XP) 정보는 이 이벤트에 없어서 카드에 표시하지 않는다 (수락 이후에 정해지는 값).
 */
@Component
@RequiredArgsConstructor
public class QuestOfferSlackNotifier {

    private static final String ACCEPT_ACTION_ID = "quest_offer_accept";
    private static final String REJECT_ACTION_ID = "quest_offer_reject";
    private static final String FALLBACK_TEXT = "오늘의 Quest 제안이 도착했어요";

    private final SlackApiClient slackApiClient;
    private final ObjectMapper objectMapper;

    public SlackMessageResult sendOffer(String slackId, QuestSuggestedData data) {
        return slackApiClient.postMessage(slackId, buildBlocksJson(data), FALLBACK_TEXT);
    }

    // Block Kit JSON 손으로 조립
    private String buildBlocksJson(QuestSuggestedData data) {
        String suggestionId = data.suggestionId().toString();

        ArrayNode blocks = objectMapper.createArrayNode();

        ObjectNode section = blocks.addObject();
        section.put("type", "section");
        section.putObject("text")
                .put("type", "mrkdwn")
                .put("text", "*" + data.title() + "*");

        ObjectNode actions = blocks.addObject();
        actions.put("type", "actions");
        ArrayNode elements = actions.putArray("elements");
        elements.add(buttonNode("수락", ACCEPT_ACTION_ID, suggestionId, "primary"));
        elements.add(buttonNode("거절", REJECT_ACTION_ID, suggestionId, "danger"));

        return blocks.toString();
    }

    // 버튼 하나 생성
    private ObjectNode buttonNode(String label, String actionId, String value, String style) {
        ObjectNode button = objectMapper.createObjectNode();
        button.put("type", "button");
        button.putObject("text")
                .put("type", "plain_text")
                .put("text", label);
        button.put("style", style);
        button.put("action_id", actionId);
        button.put("value", value);
        return button;
    }
}
