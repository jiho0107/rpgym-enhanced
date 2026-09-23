package com.workoutdone.rpgym.notification.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackApiClientTest {

    @Mock
    private MethodsClient methodsClient;

    private SlackApiClient slackApiClient;

    @BeforeEach
    void setUp() {
        slackApiClient = new SlackApiClient(methodsClient);
    }

    @Test
    @DisplayName("postMessage(channel, text) — 성공하면 channel/ts를 담아 반환한다")
    void postMessageTextSucceeds() throws Exception {
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        response.setOk(true);
        response.setChannel("D0123456789");
        response.setTs("1732147200.000100");
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);

        SlackMessageResult result = slackApiClient.postMessage("U0123456789", "테스트 메시지");

        assertThat(result.channel()).isEqualTo("D0123456789");
        assertThat(result.ts()).isEqualTo("1732147200.000100");

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
        response.setChannel("D0123456789");
        response.setTs("1732147200.000100");
        when(methodsClient.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);

        SlackMessageResult result = slackApiClient.postMessage(
                "U0123456789", "[{\"type\":\"section\"}]", "폴백 텍스트");

        assertThat(result.channel()).isEqualTo("D0123456789");

        ArgumentCaptor<ChatPostMessageRequest> captor = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(methodsClient).chatPostMessage(captor.capture());
        assertThat(captor.getValue().getBlocksAsString()).isEqualTo("[{\"type\":\"section\"}]");
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
}
