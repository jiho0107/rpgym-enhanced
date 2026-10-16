package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferCardUpdater;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferCards;
import com.workoutdone.rpgym.notification.questoffer.application.DecisionRequestResult;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferSlackActions;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러는 클릭을 "접수"만 한다 -- game-service를 부르지 않고, 접수(DB 커밋) 후 바로 200을 준다.
 * 중복 클릭이면 눌린 카드를 현재 상태에 맞춰 바꾸도록 카드 갱신을 맡긴다.
 */
@ExtendWith(MockitoExtension.class)
class SlackInteractionControllerTest {

    private static final UUID SUGGESTION_ID = UUID.fromString("3f2ac1de-7b91-4c5a-9e21-8a6b7c9d0e12");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TITLE = "20분 산책하기";
    private static final String TIMESTAMP = "1700000000";
    private static final String SIGNATURE = "v0=doesnt-matter-verifier-is-mocked";

    private static final String CLICKED_CHANNEL = "D0123456789";
    private static final String CLICKED_TS = "1732147300.000300";
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T0123/456/abcdef";
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Mock
    private SlackSignatureVerifier signatureVerifier;

    @Mock
    private QuestOfferService questOfferService;

    @Mock
    private QuestOfferCardUpdater questOfferCardUpdater;

    // 카드 선택 결과까지 확인하려고 실제 객체를 쓴다 (순수 JSON 조립이라 외부 의존이 없다).
    private final QuestOfferCards questOfferCards = new QuestOfferCards(new ObjectMapper());

    private SlackInteractionController controller;

    @BeforeEach
    void setUp() {
        controller = new SlackInteractionController(
                signatureVerifier, new ObjectMapper(), questOfferService, questOfferCards, questOfferCardUpdater);
    }

    // SlackRawBodyFilter가 필터 체인 맨 앞에서 캐싱해둔 원본 바이트를 request attribute로 흉내낸다.
    private HttpServletRequest requestWithBody(String payloadJson) {
        String encoded = "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8);
        byte[] rawBody = encoded.getBytes(StandardCharsets.UTF_8);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SlackRawBodyFilter.RAW_BODY_ATTRIBUTE)).thenReturn(rawBody);
        return request;
    }

    // 실제 Slack block_actions payload처럼 container(누른 메시지 위치)와 response_url을 담는다.
    private HttpServletRequest clickRequest(String actionId) {
        return requestWithBody("""
                {"actions":[{"action_id":"%s","value":"%s"}],
                 "user":{"id":"U0123456789"},
                 "container":{"type":"message","channel_id":"%s","message_ts":"%s"},
                 "response_url":"%s"}
                """.formatted(actionId, SUGGESTION_ID, CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL));
    }

    private static QuestOffer sentOffer() {
        QuestOffer offer = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, USER_ID, TITLE);
        offer.markSent(CLICKED_CHANNEL, "1732147200.000100", NOW);
        return offer;
    }

    private static QuestOffer processingOffer(QuestOfferAction firstClick) {
        QuestOffer offer = sentOffer();
        offer.requestDecision(firstClick, CLICKED_CHANNEL, "card-1-ts", null, NOW);
        offer.claim(NOW, Duration.ofSeconds(30));
        return offer;
    }

    @Test
    @DisplayName("서명 검증 실패 — 아무것도 접수하지 않고 INVALID_SLACK_SIGNATURE를 던진다")
    void invalidSignatureIsRejected() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.INVALID_SLACK_SIGNATURE);

        verifyNoInteractions(questOfferService, questOfferCardUpdater);
    }

    @Test
    @DisplayName("수락 클릭 — 누른 카드 위치와 response_url을 담아 ACCEPT로 접수하고 200을 반환한다. 카드 갱신은 하지 않는다")
    void acceptClickIsReceived() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        QuestOffer offer = sentOffer();
        when(questOfferService.requestDecision(SUGGESTION_ID, QuestOfferAction.ACCEPT, CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL))
                .thenReturn(new DecisionRequestResult(true, offer));

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.ACCEPT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(questOfferService).requestDecision(
                SUGGESTION_ID, QuestOfferAction.ACCEPT, CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL);
        // 접수된 클릭의 카드("처리 중")는 스케줄러가 처리하면서 바꾼다. 컨트롤러는 건드리지 않는다.
        verifyNoInteractions(questOfferCardUpdater);
    }

    @Test
    @DisplayName("거절 클릭 — REJECT로 접수한다")
    void rejectClickIsReceived() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.requestDecision(SUGGESTION_ID, QuestOfferAction.REJECT, CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL))
                .thenReturn(new DecisionRequestResult(true, sentOffer()));

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.REJECT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(questOfferService).requestDecision(
                SUGGESTION_ID, QuestOfferAction.REJECT, CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL);
    }

    @Test
    @DisplayName("⑥-a 중복 클릭(첫 클릭이 처리 중) — 200을 주고, 눌린 카드를 '다른 메시지에서 처리 중' 카드로 바꾸도록 맡긴다")
    void duplicateClickWhileProcessingUpdatesClickedCard() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        when(questOfferService.requestDecision(any(), any(), any(), any(), any()))
                .thenReturn(new DecisionRequestResult(false, offer));

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.REJECT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // 이번 클릭의 카드 위치와 response_url로 바꾼다 (DB의 첫 클릭 카드 위치가 아니라)
        verify(questOfferCardUpdater).updateDuplicateClickCard(
                CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL, questOfferCards.handledElsewhere(TITLE));
    }

    @Test
    @DisplayName("⑥-b 중복 클릭(처리 끝남) — 눌린 카드를 DB의 실제 결과(첫 클릭의 수락) 카드로 바꾼다")
    void duplicateClickAfterCompletedShowsRecordedResult() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.complete();
        when(questOfferService.requestDecision(any(), any(), any(), any(), any()))
                .thenReturn(new DecisionRequestResult(false, offer));

        // 카드 2에서는 거절을 눌렀지만
        controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.REJECT));

        // 실제로 처리된 수락 결과로 보여준다
        verify(questOfferCardUpdater).updateDuplicateClickCard(
                CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL, questOfferCards.completed(TITLE, QuestOfferAction.ACCEPT));
    }

    @Test
    @DisplayName("⑥-b 중복 클릭(CLOSED로 끝남) — 눌린 카드를 종료 사유 카드로 바꾼다")
    void duplicateClickAfterClosedShowsCloseReason() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.close(QuestOfferCloseReason.EXPIRED);
        when(questOfferService.requestDecision(any(), any(), any(), any(), any()))
                .thenReturn(new DecisionRequestResult(false, offer));

        controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.ACCEPT));

        verify(questOfferCardUpdater).updateDuplicateClickCard(
                CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL, questOfferCards.closed(TITLE, QuestOfferCloseReason.EXPIRED));
    }

    @Test
    @DisplayName("동시 클릭으로 낙관적 락 충돌 — 에러를 돌려주지 않고 한 번 더 시도해 중복 클릭으로 처리한다")
    void optimisticLockConflictIsRetriedOnce() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        QuestOffer offer = processingOffer(QuestOfferAction.REJECT);
        when(questOfferService.requestDecision(any(), any(), any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(QuestOffer.class, SUGGESTION_ID))
                .thenReturn(new DecisionRequestResult(false, offer));

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.ACCEPT));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(questOfferService, times(2)).requestDecision(any(), any(), any(), any(), any());
        verify(questOfferCardUpdater).updateDuplicateClickCard(
                CLICKED_CHANNEL, CLICKED_TS, RESPONSE_URL, questOfferCards.handledElsewhere(TITLE));
    }

    @Test
    @DisplayName("quest_offers에 없는 suggestionId — QUEST_OFFER_NOT_FOUND가 그대로 전파되고 카드 갱신도 하지 않는다")
    void offerNotFoundPropagates() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.requestDecision(any(), any(), any(), any(), any()))
                .thenThrow(new BaseException(NotificationErrorCode.QUEST_OFFER_NOT_FOUND));

        assertThatThrownBy(() ->
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest(QuestOfferSlackActions.ACCEPT)))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", NotificationErrorCode.QUEST_OFFER_NOT_FOUND);

        verifyNoInteractions(questOfferCardUpdater);
    }

    @Test
    @DisplayName("알 수 없는 action_id — 접수하지 않고 그냥 200을 반환한다")
    void unknownActionIdIsIgnored() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);

        ResponseEntity<Void> response =
                controller.handleInteraction(SIGNATURE, TIMESTAMP, clickRequest("quest_offer_snooze"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(questOfferService, questOfferCardUpdater);
    }

    @Test
    @DisplayName("payload에 container/response_url이 없어도 접수는 한다 (카드 위치는 null로 넘긴다)")
    void missingContainerStillReceivesClick() {
        when(signatureVerifier.isValid(any(), any(), any())).thenReturn(true);
        when(questOfferService.requestDecision(SUGGESTION_ID, QuestOfferAction.ACCEPT, null, null, null))
                .thenReturn(new DecisionRequestResult(true, sentOffer()));

        ResponseEntity<Void> response = controller.handleInteraction(SIGNATURE, TIMESTAMP, requestWithBody("""
                {"actions":[{"action_id":"%s","value":"%s"}],"user":{"id":"U0123456789"}}
                """.formatted(QuestOfferSlackActions.ACCEPT, SUGGESTION_ID)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(questOfferService).requestDecision(SUGGESTION_ID, QuestOfferAction.ACCEPT, null, null, null);
        verify(questOfferCardUpdater, never()).updateDuplicateClickCard(any(), any(), any(), any());
    }
}
