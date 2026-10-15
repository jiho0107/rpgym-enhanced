package com.workoutdone.rpgym.notification.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.chat.ChatUpdateResponse;
import com.slack.api.webhook.WebhookResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackApiClientTest {

    private static final String CHANNEL = "D0123456789";
    private static final String TS = "1732147200.000100";
    private static final String BLOCKS = "[{\"type\":\"section\"}]";
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T0123/456/abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MethodsClient methodsClient;

    @Mock
    private Slack slack;

    private SlackApiClient slackApiClient;

    @BeforeEach
    void setUp() {
        slackApiClient = new SlackApiClient(methodsClient, slack, objectMapper);
    }

    private static ChatUpdateResponse updateResponse(boolean ok, String error) {
        ChatUpdateResponse response = new ChatUpdateResponse();
        response.setOk(ok);
        response.setError(error);
        return response;
    }

    private static WebhookResponse webhookResponse(int code, String body) {
        return WebhookResponse.builder().code(code).body(body).build();
    }

    // ── postMessage ─────────────────────────────────────────────────────

    @Test
    @DisplayName("postMessage(channel, text) — 성공하면 channel/ts를 담아 반환한다")
    void postMessageTextSucceeds() throws Exception {
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setChannel(CHANNEL);
        response.setTs(TS);
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);

        SlackMessageResult result = slackApiClient.postMessage("U0123456789", "테스트 메시지");

        assertThat(result.channel()).isEqualTo(CHANNEL);
        assertThat(result.ts()).isEqualTo(TS);

        ArgumentCaptor<ChatPostMessageRequest> captor = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(methodsClient).chatPostMessage(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo("U0123456789");
        assertThat(captor.getValue().getText()).isEqualTo("테스트 메시지");
    }

    @Test
    @DisplayName("postMessage(channel, blocksJson, fallbackText) — blocks와 대체 텍스트를 함께 실어 보낸다")
    void postMessageWithBlocksSucceeds() throws Exception {
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setChannel(CHANNEL);
        response.setTs(TS);
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);

        SlackMessageResult result = slackApiClient.postMessage("U0123456789", BLOCKS, "폴백 텍스트");

        assertThat(result.channel()).isEqualTo(CHANNEL);

        ArgumentCaptor<ChatPostMessageRequest> captor = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(methodsClient).chatPostMessage(captor.capture());
        assertThat(captor.getValue().getBlocksAsString()).isEqualTo(BLOCKS);
        assertThat(captor.getValue().getText()).isEqualTo("폴백 텍스트");
    }

    @Test
    @DisplayName("Slack이 ok:false로 응답 — SlackMessageSendException을 던진다")
    void respondsNotOkThrows() throws Exception {
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(false);
        response.setError("channel_not_found");
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> slackApiClient.postMessage("U0123456789", "테스트"))
                .isInstanceOf(SlackMessageSendException.class)
                .hasMessageContaining("channel_not_found");
    }

    @Test
    @DisplayName("네트워크 오류(IOException) — SlackMessageSendException으로 감싸 던진다")
    void ioExceptionIsWrapped() throws Exception {
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class)))
                .thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> slackApiClient.postMessage("U0123456789", "테스트"))
                .isInstanceOf(SlackMessageSendException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    // ── updateMessage (chat.update) ─────────────────────────────────────

    @Test
    @DisplayName("updateMessage — channel/ts로 메시지를 지정하고 blocks와 대체 텍스트를 실어 chat.update를 호출한다")
    void updateMessageSendsChatUpdate() throws Exception {
        when(methodsClient.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(updateResponse(true, null));

        slackApiClient.updateMessage(CHANNEL, TS, BLOCKS, "⏳ 처리 중");

        ArgumentCaptor<ChatUpdateRequest> captor = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(methodsClient).chatUpdate(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(CHANNEL);
        assertThat(captor.getValue().getTs()).isEqualTo(TS);
        assertThat(captor.getValue().getBlocksAsString()).isEqualTo(BLOCKS);
        assertThat(captor.getValue().getText()).isEqualTo("⏳ 처리 중");
    }

    @Test
    @DisplayName("updateMessage — Slack이 ok:false(예: message_not_found)면 SlackMessageUpdateException을 던진다")
    void updateMessageNotOkThrows() throws Exception {
        when(methodsClient.chatUpdate(any(ChatUpdateRequest.class)))
                .thenReturn(updateResponse(false, "message_not_found"));

        assertThatThrownBy(() -> slackApiClient.updateMessage(CHANNEL, TS, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class)
                .hasMessageContaining("message_not_found");
    }

    @Test
    @DisplayName("updateMessage — 네트워크 오류는 SlackMessageUpdateException으로 감싼다 (발송 실패 예외와 구분)")
    void updateMessageIoExceptionIsWrapped() throws Exception {
        when(methodsClient.chatUpdate(any(ChatUpdateRequest.class))).thenThrow(new IOException("timeout"));

        assertThatThrownBy(() -> slackApiClient.updateMessage(CHANNEL, TS, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    // ── respond (response_url) ──────────────────────────────────────────

    @Test
    @DisplayName("respond — response_url로 replace_original=true, text, blocks를 담은 JSON을 보낸다")
    void respondSendsReplaceOriginalPayload() throws Exception {
        when(slack.send(eq(RESPONSE_URL), anyString())).thenReturn(webhookResponse(200, "ok"));

        slackApiClient.respond(RESPONSE_URL, BLOCKS, "✅ 퀘스트를 수락했어요");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(slack).send(eq(RESPONSE_URL), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("replace_original").asBoolean()).isTrue();
        assertThat(payload.get("text").asText()).isEqualTo("✅ 퀘스트를 수락했어요");
        // blocks는 문자열로 한 번 더 감싸지지 않고 JSON 배열 그대로 들어가야 Slack이 카드로 그린다.
        assertThat(payload.get("blocks").isArray()).isTrue();
        assertThat(payload.get("blocks").get(0).get("type").asText()).isEqualTo("section");
    }

    @Test
    @DisplayName("respond — 200이어도 본문이 {\"ok\":false}면 실패로 본다 (만료/횟수 초과)")
    void respondOkFalseBodyThrows() throws Exception {
        when(slack.send(eq(RESPONSE_URL), anyString()))
                .thenReturn(webhookResponse(200, "{\"ok\":false,\"error\":\"expired_url\"}"));

        assertThatThrownBy(() -> slackApiClient.respond(RESPONSE_URL, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class)
                .hasMessageContaining("expired_url");
    }

    @Test
    @DisplayName("respond — 200 + {\"ok\":true} 본문은 성공으로 본다")
    void respondOkTrueBodySucceeds() throws Exception {
        when(slack.send(eq(RESPONSE_URL), anyString())).thenReturn(webhookResponse(200, "{\"ok\":true}"));

        assertThatCode(() -> slackApiClient.respond(RESPONSE_URL, BLOCKS, "텍스트")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("respond — 2xx가 아니면 SlackMessageUpdateException을 던진다")
    void respondNon2xxThrows() throws Exception {
        when(slack.send(eq(RESPONSE_URL), anyString())).thenReturn(webhookResponse(404, "used_url"));

        assertThatThrownBy(() -> slackApiClient.respond(RESPONSE_URL, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("respond — 네트워크 오류는 SlackMessageUpdateException으로 감싼다")
    void respondIoExceptionIsWrapped() throws Exception {
        when(slack.send(eq(RESPONSE_URL), anyString())).thenThrow(new IOException("timeout"));

        assertThatThrownBy(() -> slackApiClient.respond(RESPONSE_URL, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.slack.com/actions/T0/1/a",   // https가 아님
            "https://evil.example.com/actions/T0/1/a", // Slack 호스트가 아님
            "https://hooks.slack.com.evil.com/a",      // 호스트 이름 앞부분만 같은 경우
            "not a url"
    })
    @DisplayName("respond — Slack이 발급한 response_url이 아니면 요청을 보내지 않고 실패한다")
    void respondRejectsNonSlackUrl(String url) throws Exception {
        assertThatThrownBy(() -> slackApiClient.respond(url, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class);

        verify(slack, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("respond — response_url이 없으면(null) 요청을 보내지 않고 실패한다")
    void respondRejectsNullUrl() throws Exception {
        assertThatThrownBy(() -> slackApiClient.respond(null, BLOCKS, "텍스트"))
                .isInstanceOf(SlackMessageUpdateException.class);

        verify(slack, never()).send(anyString(), anyString());
    }
}
