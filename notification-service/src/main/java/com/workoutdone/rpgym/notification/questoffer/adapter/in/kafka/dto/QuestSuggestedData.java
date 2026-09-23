package com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * game-service의 QuestToNotification과 동일한 필드 구조
 *
 * questId가 없다 -- 이 시점엔 아직 Quest가 만들어지지 않았다 (사용자가 수락해야 생긴다).
 * rewardXp도 없다 -- 보상은 제안 시점 이후(수락 시점)에 정해지는 값이라 이벤트에 실리지 않는다.
 * 그래서 Slack 카드에 보상 문구를 넣지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuestSuggestedData(
        UUID suggestionId,
        String title,
        String metric,
        int targetValue,
        Instant expiresAt
) {
}
