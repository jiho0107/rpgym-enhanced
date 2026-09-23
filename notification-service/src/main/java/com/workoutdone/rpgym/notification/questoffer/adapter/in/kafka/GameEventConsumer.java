package com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka;

import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto.GameEventEnvelope;
import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto.QuestSuggestedData;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.slack.QuestOfferSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.application.QuestOfferService;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * game.events 토픽에서 QUEST_SUGGESTED만 골라 처리한다.
 *
 * 이 클래스가 트랜잭션을 직접 열지 않고 세 단계(DB 준비 -> Slack 호출 -> DB 확정)를
 * 순서대로 오케스트레이션하는 이유는 QuestOfferService 쪽 주석과 같다
 * -- Slack 호출은 외부 I/O라 DB 트랜잭션 안에 두면 안 된다.
 *
 * 발송 직전에 game-service의 현재 상태를 다시 조회하지 않는다.
 * 이미 만료(30분 지남)됐을 제안이라도 (consumer lag 등으로) 이 이벤트가 뒤늦게 도착하면 그대로 발송한다.
 * 어차피 사용자가 뒤늦게 눌러도 game-service의 accept/reject API가 SUGGESTION_EXPIRED(410)로 거절하므로, 여기서 미리 걸러낼 필요가 없다.
 *
 * 예외를 던지느냐 마느냐 -- game-service의 HealthEventConsumer와 같은 원칙이다.
 * 재시도로 해결되는 것(Slack/user-service 일시 장애)은 던져서 Kafka가 재전송하게 하고,
 * 계약 위반(필수 필드 누락)은 로그만 남기고 넘어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventConsumer {

    private static final String QUEST_SUGGESTED = "QUEST_SUGGESTED";

    private final ObjectMapper objectMapper;
    private final QuestOfferService questOfferService;
    private final UserServiceClient userServiceClient;
    private final QuestOfferSlackNotifier questOfferSlackNotifier;
    private final RetryTemplate retryTemplate;

    @KafkaListener(topics = "${rpgym.kafka.game-events-topic}")
    public void consume(String message) {
        GameEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, GameEventEnvelope.class);
        } catch (JsonProcessingException e) {
            // 재시도해도 같은 문자열이 같은 곳에서 깨진다.
            log.error("game event 역직렬화 실패. 건너뛴다. message={}", message, e);
            return;
        }

        if (envelope.eventType() == null || envelope.userId() == null) {
            log.error("envelope 필수 필드 누락. 건너뛴다. eventType={} userId={}",
                    envelope.eventType(), envelope.userId());
            return;
        }

        if (!QUEST_SUGGESTED.equals(envelope.eventType())) {
            // 이 토픽에 다른 이벤트(QUEST_CREATED, QUEST_COMPLETED 등)가 실려도 offset은 정상적으로 밀려야 하므로 예외를 던지지 않는다.
            log.debug("QUEST_SUGGESTED가 아니라 건너뛴다. eventType={}", envelope.eventType());
            return;
        }

        MDC.put("eventId", String.valueOf(envelope.eventId()));
        MDC.put("userId", String.valueOf(envelope.userId()));
        try {
            handle(envelope);
        } finally {
            MDC.remove("eventId");
            MDC.remove("userId");
        }
    }

    private void handle(GameEventEnvelope envelope) {
        QuestSuggestedData data = convert(envelope.data());
        if (data == null || data.suggestionId() == null || data.title() == null) {
            log.error("QUEST_SUGGESTED 필수 필드 누락. 건너뛴다. data={}", envelope.data());
            return;
        }

        Optional<UUID> offerId = questOfferService.prepareForSend(data.suggestionId(), envelope.userId());
        if (offerId.isEmpty()) { //이미 처리된 제안인 경우
            return;
        }

        UserInfoResponse userInfo = userServiceClient.getUserInfo(envelope.userId());
        if (userInfo.slackId() == null || userInfo.slackId().isBlank()) {
            // 재시도해도 slackId가 저절로 생기지 않으니 계약 위반으로 보고 넘어간다.
            log.error("slackId가 없어 발송할 수 없다. userId={}", envelope.userId());
            return;
        }

        SlackMessageResult result = questOfferSlackNotifier.sendOffer(userInfo.slackId(), data);

        // Slack 발송은 이미 성공한 뒤라 markSent(순수 DB 쓰기)만 짧게 로컬 재시도한다.
        // QuestOfferService가 아니라 여기서 감싸는 이유 --
        // retryTemplate.execute가 markSent를 프록시(빈 경계) 바깥에서 호출해야 매 시도마다 새 트랜잭션이 열린다.
        // 이마저 다 실패하면 예외가 그대로 전파되어 Kafka가 메시지 전체를 재시도하게 된다
        // (그 경우 prepareForSend가 PENDING을 다시 발견해 Slack을 재호출할 수 있음).
        retryTemplate.execute(context -> {
            questOfferService.markSent(offerId.get(), result.channel(), result.ts());
            return null;
        });

        log.info("Quest 제안 Slack 발송 완료. suggestionId={} userId={} channel={}",
                data.suggestionId(), envelope.userId(), result.channel());
    }

    private QuestSuggestedData convert(JsonNode data) {
        if (data == null || data.isNull()) {
            log.error("data가 비어 있다. 건너뛴다.");
            return null;
        }
        try {
            return objectMapper.treeToValue(data, QuestSuggestedData.class);
        } catch (JsonProcessingException e) {
            log.error("data 변환 실패. data={}", data, e);
            return null;
        }
    }
}
