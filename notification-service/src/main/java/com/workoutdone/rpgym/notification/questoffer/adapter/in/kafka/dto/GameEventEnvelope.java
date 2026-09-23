package com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * game-service가 game.events 토픽에 싣는 공통 envelope.
 * game-service의 HealthEventEnvelope와 같은 이유로 eventType을 String, data를 JsonNode로 받는다 --
 * eventType을 먼저 보고 나서야 data를 어떤 타입으로 변환할지 알 수 있고,
 * 알 수 없는 타입이 와도 역직렬화 자체는 성공해야 무한 재시도를 피할 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameEventEnvelope(
        UUID eventId,
        String eventType,
        UUID userId,
        JsonNode data
) {
}
