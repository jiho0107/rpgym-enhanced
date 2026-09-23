package com.workoutdone.rpgym.game.achievement.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Health 가 health.events 토픽에 싣는 공통 envelope 을 업적 쪽에서 읽는 형태.
 *
 * quest 패키지에 같은 모양의 클래스가 있지만 일부러 가져다 쓰지 않는다 -- 그쪽 담당이 필드를 바꾸면
 * 업적 컨슈머가 같이 깨진다. 계약의 주인은 Health 이지 quest 가 아니므로 각자 Health 를 본다.
 *
 * eventType 을 String 으로 받는다. 모르는 값이 와도 역직렬화가 터지지 않아야 "무시" 로 끝낼 수 있다.
 * data 는 JsonNode 다. DAILY_GOAL_COMPLETED 일 때만 DailyGoalCompletedData 로 바꾼다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthEventEnvelope(
        UUID eventId,
        String eventType,
        UUID userId,
        JsonNode data
) {
}
