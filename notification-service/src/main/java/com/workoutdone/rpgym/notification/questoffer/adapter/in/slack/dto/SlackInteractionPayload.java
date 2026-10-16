package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * [Slack이 보내는 JSON 구조를 그대로 옮겨 담는 DTO]
 * Slack Block Kit 인터랙션 페이로드 중 필요한 부분만 옮겨 담는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackInteractionPayload(
        List<Action> actions,
        User user,
        /** 사용자가 실제로 누른 메시지의 위치. 같은 제안 카드가 중복 발송돼 두 장이어도 누른 카드를 정확히 고칠 수 있다. */
        Container container,
        /** 누른 메시지를 고칠 수 있는 Slack 발급 주소. chat.update 실패 시 보조 수단 (제약 조건: 30분 안에 최대 5회 가능) */
        @JsonProperty("response_url") String responseUrl
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(
            @JsonProperty("action_id") String actionId,
            String value
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
            String id
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Container(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("message_ts") String messageTs
    ) {
    }
}
