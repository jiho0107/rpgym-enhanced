package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.GameServiceClient;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferSlackActions;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * game-service의 실제 계약(/api/v2/internal/games/quest-suggestions/{suggestionId}/accept|reject,
 * X-User-Id 헤더, 4xx는 SuggestionErrorCode)이 mvp/test-yujun 브랜치대로 develop에 반영되어 있다고
 * 전제하고 작성한 테스트다. GameServiceClient 자체는 Feign 인터페이스라 실제 호출은 mock으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class SlackInteractionControllerTest {

    private static final UUID SUGGESTION_ID = UUID.fromString("3f2ac1de-7b91-4c5a-9e21-8a6b7c9d0e12");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TIMESTAMP = "1700000000";
    private static final String SIGNATURE = "v0=doesnt-matter-verifier-is-mocked";

    @Mock
    private SlackSignatureVerifier signatureVerifier;

    @Mock
    private QuestOfferService questOfferService;

    @Mock
    private GameServiceClient gameServiceClient;

    private SlackInteractionController controller;

    @BeforeEach
    void setUp() {
        controller = new SlackInteractionController(
                signatureVerifier, new ObjectMapper(), questOfferService, gameServiceClient);
    }

    // SlackRawBodyFilter가 필터 체인 맨 앞에서 캐싱해둔 원본 바이트를 request attribute로 흉내낸다.
    private HttpServletRequest requestWithBody(String actionId) {
        String json = """
                {"actions":[{"action_id":"%s","value":"%s"}],"user":{"id":"U0123456789"}}
                """.formatted(actionId, SUGGESTION_ID).strip();
        String encoded = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        byte[] rawBody = encoded.getBytes(StandardCharsets.UTF_8);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SlackRawBodyFilter.RAW_BODY_ATTRIBUTE)).thenReturn(rawBody);
        return request;
    }

    private QuestOffer sentOffer() {
        return QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, USER_ID);
    }

    @Test
    @DisplayName("서명 검증 실패 — game-service를 부르지 않고 INVALID_SLACK_SIGNATURE를 던진다")
    void invalidSignatureIsRejected() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.INVALID_SLACK_SIGNATURE);

        verifyNoInteractions(questOfferService, gameServiceClient);
    }

    @Test
    @DisplayName("수락 버튼 — game-service accept를 호출하고 200을 반환한다")
    void acceptCallsGameService() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(gameServiceClient).accept(SUGGESTION_ID, USER_ID);
        verify(gameServiceClient, never()).reject(any(), any());
    }

    @Test
    @DisplayName("거절 버튼 — game-service reject를 호출하고 200을 반환한다")
    void rejectCallsGameService() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.REJECT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(gameServiceClient).reject(SUGGESTION_ID, USER_ID);
        verify(gameServiceClient, never()).accept(any(), any());
    }

    @Test
    @DisplayName("quest_offers에 없는 suggestionId — 예외가 그대로 전파되고 game-service는 부르지 않는다")
    void offerNotFoundPropagates() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID))
                .thenThrow(new BaseException(NotificationErrorCode.QUEST_OFFER_NOT_FOUND));

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.QUEST_OFFER_NOT_FOUND);

        verifyNoInteractions(gameServiceClient);
    }

    @Test
    @DisplayName("game-service가 4xx(이미 처리됨 등)로 응답 — 재시도 의미가 없다고 보고 200을 반환한다")
    void gameServiceBusinessRejectionStillReturns200() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());
        doThrow(feignExceptionWithStatus(409)).when(gameServiceClient).accept(SUGGESTION_ID, USER_ID);

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("game-service가 5xx로 응답 — 재시도해볼 가치가 있다고 보고 GAME_SERVICE_CALL_FAILED(502)를 던진다")
    void gameServiceServerErrorPropagatesAs502() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());
        doThrow(feignExceptionWithStatus(500)).when(gameServiceClient).accept(SUGGESTION_ID, USER_ID);

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.GAME_SERVICE_CALL_FAILED);
    }

    @Test
    @DisplayName("game-service 호출 자체가 실패(타임아웃 등) — GAME_SERVICE_CALL_FAILED(502)를 던져 Slack이 재전송하게 한다")
    void gameServiceCallFailurePropagatesAs502() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());
        doThrow(new RuntimeException("connection refused")).when(gameServiceClient).accept(SUGGESTION_ID, USER_ID);

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.GAME_SERVICE_CALL_FAILED);
    }

    @Test
    @DisplayName("알 수 없는 action_id — game-service를 부르지 않고 그냥 200을 반환한다")
    void unknownActionIdIsIgnored() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.getBySuggestionId(SUGGESTION_ID)).thenReturn(sentOffer());

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody("quest_offer_snooze"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(gameServiceClient);
    }

    // FeignException은 protected 생성자만 있어서, 실제 SDK가 쓰는 정적 팩토리(errorStatus)로 만든다.
    private FeignException feignExceptionWithStatus(int status) {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "http://game-service/api/v2/internal/games/quest-suggestions/" + SUGGESTION_ID + "/accept",
                Map.of(),
                (byte[]) null,
                (Charset) null,
                null);
        Response response = Response.builder()
                .status(status)
                .reason("test")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("GameServiceClient#accept(UUID,UUID)", response);
    }
}
