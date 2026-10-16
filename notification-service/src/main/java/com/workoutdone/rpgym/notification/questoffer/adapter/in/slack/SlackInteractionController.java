package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.adapter.in.slack.dto.SlackInteractionPayload;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferCardUpdater;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferCards;
import com.workoutdone.rpgym.notification.questoffer.application.DecisionRequestResult;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferSlackActions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Slack에서 Quest 제안 카드의 수락/거절 버튼을 누르면 Slack이 여기로 콜백을 보낸다.
 * 게이트웨이 SecurityConfig의 permitAll 목록에 이 경로가 있어야 한다 -- Slack은 우리 JWT가 없고,
 * 대신 서명(SlackSignatureVerifier)으로 자신을 증명한다.
 *
 * 서명 검증에는 Spring/Tomcat의 어떤 컴포넌트도 아직 건드리지 않은 원본 바이트가 필요하다.
 * @RequestBody로 받으면 form 파라미터 파싱을 거치면서 body가 살짝 달라지는 걸 확인해서(예: '*' 문자 관련 URLEncoder 재인코딩 차이),
 *  SlackRawBodyFilter가 필터 체인 맨 앞에서 미리 캐싱해둔 원본을 attribute로 꺼내 쓴다.
 *
 * 여기서는 클릭을 "접수"만 하고 game-service는 부르지 않는다.
 * Slack Interactivity는 응답 실패/타임아웃 시 재전송하지 않아서, game-service를 기다리다 3초를 넘기면 클릭이 유실된다.
 * 접수(quest_offers를 PROCESSING으로 커밋)만 하고 바로 200을 주면, game-service 반영과 재시도는 스케줄러가 맡는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications/slack")
@RequiredArgsConstructor
public class SlackInteractionController {

    private static final String PAYLOAD_PARAM_PREFIX = "payload=";

    private final SlackSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final QuestOfferService questOfferService;
    private final QuestOfferCards questOfferCards;
    private final QuestOfferCardUpdater questOfferCardUpdater;

    // Slack 버튼 클릭 콜백의 진입점.
    // 서명 검증 -> payload 파싱 -> 클릭 접수(DB 커밋) -> 200 응답. 중복 클릭이면 눌린 카드 갱신을 비동기로 맡긴다.
    @PostMapping(value = "/interactions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> handleInteraction(
            @RequestHeader("X-Slack-Signature") String signature,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp,
            HttpServletRequest request
    ) {
        // SlackRawBodyFilter가 필터 체인 맨 앞에서 미리 읽어 캐싱해둔 원본 바이트.
        byte[] rawBodyBytes = (byte[]) request.getAttribute(SlackRawBodyFilter.RAW_BODY_ATTRIBUTE);

        if (!signatureVerifier.isValid(signature, timestamp, rawBodyBytes)) {
            throw new BaseException(NotificationErrorCode.INVALID_SLACK_SIGNATURE);
        }

        // 서명 검증이 끝난 후에는 원본 바이트를 UTF-8로 디코딩해서 기존 payload 파싱 로직에 그대로 넘김
        String rawBody = new String(rawBodyBytes, StandardCharsets.UTF_8);
        SlackInteractionPayload payload = parsePayload(rawBody);
        SlackInteractionPayload.Action action = payload.actions().get(0);
        UUID suggestionId = UUID.fromString(action.value());

        QuestOfferAction requestedAction = toAction(action.actionId());
        if (requestedAction == null) {
            log.error("알 수 없는 action_id. suggestionId={} actionId={}", suggestionId, action.actionId());
            return ResponseEntity.ok().build();
        }

        // 사용자가 실제로 누른 카드의 위치. 같은 제안 카드가 두 장이어도 누른 카드를 정확히 고칠 수 있다.
        SlackInteractionPayload.Container container = payload.container();
        String clickedChannelId = container == null ? null : container.channelId();
        String clickedMessageTs = container == null ? null : container.messageTs();

        DecisionRequestResult result =
                requestDecision(suggestionId, requestedAction, clickedChannelId, clickedMessageTs, payload.responseUrl());

        if (!result.accepted()) {
            // 중복 클릭 -- DB는 그대로 두고, 눌린 카드만 현재 상태에 맞춰 바꾼다(⑥: @Async). 기다리지 않는다.
            questOfferCardUpdater.updateDuplicateClickCard(
                    clickedChannelId, clickedMessageTs, payload.responseUrl(),
                    questOfferCards.forDuplicateClick(result.offer()));
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 같은 제안에 클릭 두 개가 동시에 들어와 둘 다 SENT로 읽었다면, 늦게 커밋한 쪽은 낙관적 락 충돌이 난다.
     * 에러를 Slack에 돌려주지 않고 한 번 더 시도한다 -- 그때는 이미 PROCESSING이라 중복 클릭으로 처리된다.
     */
    private DecisionRequestResult requestDecision(UUID suggestionId, QuestOfferAction action,
                                                  String clickedChannelId, String clickedMessageTs, String responseUrl) {
        try {
            return questOfferService.requestDecision(suggestionId, action, clickedChannelId, clickedMessageTs, responseUrl);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("같은 제안에 동시에 들어온 클릭과 충돌해 다시 시도한다. suggestionId={}", suggestionId);
            return questOfferService.requestDecision(suggestionId, action, clickedChannelId, clickedMessageTs, responseUrl);
        }
    }

    private static QuestOfferAction toAction(String actionId) {
        if (QuestOfferSlackActions.ACCEPT.equals(actionId)) {
            return QuestOfferAction.ACCEPT;
        }
        if (QuestOfferSlackActions.REJECT.equals(actionId)) {
            return QuestOfferAction.REJECT;
        }
        return null;
    }

    // form-urlencoded 원본 문자열에서 payload 필드만 꺼내 디코딩하고 JSON으로 변환한다.
    private SlackInteractionPayload parsePayload(String rawBody) {
        String encoded = Arrays.stream(rawBody.split("&"))
                .filter(pair -> pair.startsWith(PAYLOAD_PARAM_PREFIX))
                .map(pair -> pair.substring(PAYLOAD_PARAM_PREFIX.length()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Slack payload 필드가 없다"));

        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(decoded, SlackInteractionPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Slack payload 파싱에 실패했다", e);
        }
    }
}
