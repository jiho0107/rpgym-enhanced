package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * [Slack이 보내는 JSON 구조를 그대로 옮겨 담는 DTO]
 * Slack Block Kit 인터랙션 페이로드 중 필요한 부분만 옮겨 담는다.
 * ////TO-DO: user/responseUrl은 이번 구현 범위에선 쓰지 않지만, 후속 작업(response_url로 메시지 갱신)에서 다시 파싱하지 않도록 미리 옮겨만 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackInteractionPayload(
        List<Action> actions,
        User user,
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
}
