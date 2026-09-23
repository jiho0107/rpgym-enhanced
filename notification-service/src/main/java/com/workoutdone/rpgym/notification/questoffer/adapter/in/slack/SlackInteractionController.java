package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.adapter.in.slack.dto.SlackInteractionPayload;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.GameServiceClient;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferSlackActions;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 서명 검증에는 Spring이 파싱하기 전의 원본 바이트가 필요해서, @RequestBody를 String으로 받아
 * form-urlencoded 파싱을 직접 한다. 이렇게 해야 Content-Type이 뭐든 원본 그대로 들어온다.
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
    private final GameServiceClient gameServiceClient;

    // Slack 버튼 클릭 콜백의 진입점.
    // 서명 검증 -> payload 파싱 -> game-service 호출 -> 200 응답까지 전체를 지휘
    @PostMapping(value = "/interactions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> handleInteraction(
            @RequestHeader("X-Slack-Signature") String signature,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp,
            @RequestBody String rawBody //서명 검증에 원본 바이트가 그대로 필요하기 때문에 @RequestBody를 String으로 받음
    ) {
        if (!signatureVerifier.isValid(signature, timestamp, rawBody)) {
            throw new BaseException(NotificationErrorCode.INVALID_SLACK_SIGNATURE);
        }

        //원본 문자열을 파싱
        SlackInteractionPayload payload = parsePayload(rawBody);
        SlackInteractionPayload.Action action = payload.actions().get(0);
        UUID suggestionId = UUID.fromString(action.value());

        // slack 발송 시점에 이미 DB(quest_offers)에 저장해둔 userId를 그대로 쓴다 -- user-service를 다시 조회할 필요가 없다.
        QuestOffer offer = questOfferService.getBySuggestionId(suggestionId);

        decide(action.actionId(), suggestionId, offer.getUserId());

        //// TO-DO: quest_offers.status를 ACCEPTED/REJECTED로 갱신
        //// TO-DO: response_url로 Slack 메시지를 처리 결과로 갱신 (payload.responseUrl() 사용)

        return ResponseEntity.ok().build();
    }

    // action_id를 보고 수락/거절 중 하나로 game-service를 호출하고, 응답에 따라 재시도할지 말지를 정한다.
    private void decide(String actionId, UUID suggestionId, UUID userId) {
        try {
            if (QuestOfferSlackActions.ACCEPT.equals(actionId)) {
                gameServiceClient.accept(suggestionId, userId);
            } else if (QuestOfferSlackActions.REJECT.equals(actionId)) {
                gameServiceClient.reject(suggestionId, userId);
            } else {
                log.error("알 수 없는 action_id. suggestionId={} actionId={}", suggestionId, actionId);
            }
        } catch (FeignException e) { //game-service가 응답은 줬는데 그 내용이 에러(4xx나 5xx)일 때
            if (e.status() >= 400 && e.status() < 500) { //4xx
                // game-service가 확정된 답(SUGGESTION_ALREADY_DECIDED 등)을 준 경우다.
                // 재시도해도 결과가 똑같으므로 return으로 조용히 끝낸다.(호출한 쪽(handleInteraction)이 200을 반환함)
                log.info("game-service가 처리 불가로 판정. suggestionId={} status={}", suggestionId, e.status());
                return;
            }
            // game-service 쪽 5xx -- 일시적 장애일 수 있으니 Slack이 재전송하게 한다.
            log.error("game-service 호출 실패(5xx). suggestionId={}", suggestionId, e);
            throw new BaseException(NotificationErrorCode.GAME_SERVICE_CALL_FAILED);
        } catch (Exception e) {
            // 타임아웃, 연결 실패 등 game-service를 아예 호출하지 못한 경우. - Slack이 재전송하게 한다.
            log.error("game-service 호출 자체가 실패. suggestionId={}", suggestionId, e);
            throw new BaseException(NotificationErrorCode.GAME_SERVICE_CALL_FAILED);
        }
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
