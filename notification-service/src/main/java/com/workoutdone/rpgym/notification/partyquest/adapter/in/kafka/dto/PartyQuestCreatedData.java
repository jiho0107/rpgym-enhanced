package com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * game-service의 PartyQuestCreatedData와 동일한 필드 구조 (eventType = PARTY_QUEST_CREATED).
 *
 * members에 파티장도 포함돼 있다. 발송 대상은 members 전원이다.
 * 시작 시각은 봉투의 occurredAt과 같은 값이라 싣지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartyQuestCreatedData(
        UUID partyQuestId,
        UUID partyId,
        UUID ownerId,
        String title,
        String metric,
        int targetValue,
        int rewardXp,
        Instant expiredAt,
        List<Member> members
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(UUID userId) {
    }
}
