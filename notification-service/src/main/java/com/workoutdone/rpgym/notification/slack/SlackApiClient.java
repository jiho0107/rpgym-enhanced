package com.workoutdone.rpgym.notification.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Slack과의 저수준 통신만 안다 -- 어떤 메시지를 보낼지는 모른다.
 * 메시지 내용(Block Kit 등) 조립은 이 패키지를 쓰는 각 도메인에서 하고,
 * 이 클래스는 만들어진 내용을 발송만 한다.
 */
@Component
@RequiredArgsConstructor
public class SlackApiClient {

    private final MethodsClient methodsClient;

    /** 버튼 없는 단순 텍스트 메시지. 연동 확인이나 모니터링 알림처럼 상호작용이 필요 없을 때 쓴다. */
    public SlackMessageResult postMessage(String channel, String text) {
        return send(ChatPostMessageRequest.builder()
                .channel(channel)
                .text(text)
                .build());
    }

    /**
     * @param channel    Slack 채널 ID 또는 사용자 ID(U...). 사용자 ID를 넘기면 Slack이 DM을 자동으로 연다.
     * @param blocksJson Block Kit blocks 배열의 JSON 문자열
     * @param fallbackText 알림 미리보기 등에 쓰이는 대체 텍스트
     */
    public SlackMessageResult postMessage(String channel, String blocksJson, String fallbackText) {
        return send(ChatPostMessageRequest.builder()
                .channel(channel)
                .blocksAsString(blocksJson)
                .text(fallbackText)
                .build());
    }

    private SlackMessageResult send(ChatPostMessageRequest request) {
        try {
            ChatPostMessageResponse response = methodsClient.chatPostMessage(request);

            if (!response.isOk()) {
                throw new SlackMessageSendException("Slack 발송 실패. error=" + response.getError());
            }

            return new SlackMessageResult(response.getChannel(), response.getTs());

        } catch (IOException | SlackApiException e) {
            throw new SlackMessageSendException("Slack API 호출에 실패했다. channel=" + request.getChannel(), e);
        }
    }
}
