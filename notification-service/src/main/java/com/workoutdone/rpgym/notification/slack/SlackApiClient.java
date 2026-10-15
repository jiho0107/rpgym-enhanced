package com.workoutdone.rpgym.notification.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.webhook.WebhookResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Slack과의 저수준 통신만 안다 -- 어떤 메시지를 보낼지는 모른다.
 * 메시지 내용(Block Kit 등) 조립은 이 패키지를 쓰는 각 도메인에서 하고,
 * 이 클래스는 만들어진 내용을 발송/수정만 한다.
 */
@Component
@RequiredArgsConstructor
public class SlackApiClient {

    /** response_url은 Slack이 발급한 이 호스트로만 보낸다. 다른 곳으로 카드 내용을 보내지 않도록 막는다. */
    private static final String RESPONSE_URL_HOST = "hooks.slack.com";

    private final MethodsClient methodsClient;
    private final Slack slack;
    private final ObjectMapper objectMapper;

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

    /**
     * chat.update로 이미 보낸 메시지를 통째로 바꾼다. 수정은 푸시 알림을 다시 울리지 않는다.
     *
     * @param channel 메시지가 있는 채널 ID. 사용자 ID(U...)가 아니라 DM 채널 ID(D...)여야 한다.
     * @param ts      바꿀 메시지의 ts
     * @throws SlackMessageUpdateException 수정에 실패하면. 호출하는 쪽이 잡아서 response_url로 넘어간다.
     */
    public void updateMessage(String channel, String ts, String blocksJson, String fallbackText) {
        ChatUpdateRequest request = ChatUpdateRequest.builder()
                .channel(channel)
                .ts(ts)
                .blocksAsString(blocksJson)
                .text(fallbackText)
                .build();
        try {
            ChatUpdateResponse response = methodsClient.chatUpdate(request);
            if (!response.isOk()) {
                throw new SlackMessageUpdateException(
                        "Slack 메시지 수정 실패. channel=" + channel + " ts=" + ts + " error=" + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            throw new SlackMessageUpdateException(
                    "Slack chat.update 호출에 실패했다. channel=" + channel + " ts=" + ts, e);
        }
    }

    /**
     * 버튼 클릭 payload의 response_url로 원래 메시지를 바꾼다(replace_original).
     * chat.update가 실패했을 때 쓰는 보조 수단이다. Slack 제한: 클릭 후 30분 안에 최대 5회.
     *
     * @throws SlackMessageUpdateException 수정에 실패하면 (만료/횟수 초과 포함)
     */
    public void respond(String responseUrl, String blocksJson, String fallbackText) {
        requireSlackResponseUrl(responseUrl);

        String payload = responsePayload(blocksJson, fallbackText);
        try {
            WebhookResponse response = slack.send(responseUrl, payload);
            if (!isSuccess(response)) {
                throw new SlackMessageUpdateException(
                        "response_url 수정 실패. code=" + response.getCode() + " body=" + response.getBody());
            }
        } catch (IOException e) {
            throw new SlackMessageUpdateException("response_url 호출에 실패했다.", e);
        }
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

    private void requireSlackResponseUrl(String responseUrl) {
        URI uri;
        try {
            uri = URI.create(responseUrl);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SlackMessageUpdateException("response_url 형식이 올바르지 않다.", e);
        }
        if (!"https".equals(uri.getScheme()) || !RESPONSE_URL_HOST.equals(uri.getHost())) {
            throw new SlackMessageUpdateException("Slack이 발급한 response_url이 아니다. host=" + uri.getHost());
        }
    }

    // {"replace_original": true, "text": ..., "blocks": [...]}
    private String responsePayload(String blocksJson, String fallbackText) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("replace_original", true);
            payload.put("text", fallbackText);
            payload.set("blocks", objectMapper.readTree(blocksJson));
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new SlackMessageUpdateException("response_url payload를 만들지 못했다.", e);
        }
    }

    // response_url은 실패해도 200 + {"ok":false}로 답하는 경우가 있어 본문까지 확인한다.
    private boolean isSuccess(WebhookResponse response) {
        Integer code = response.getCode();
        if (code == null || code < 200 || code >= 300) {
            return false;
        }
        String body = response.getBody();
        if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
            return true;
        }
        try {
            JsonNode ok = objectMapper.readTree(body).get("ok");
            return ok == null || ok.asBoolean();
        } catch (JsonProcessingException e) {
            return true;
        }
    }
}
