package com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto;

import java.util.UUID;

/** user-service의 GET /api/v1/internal/users/{userId} 응답. */
public record UserInfoResponse(
        UUID id,
        String nickname,
        String role,
        String status,
        String slackId
) {
}
