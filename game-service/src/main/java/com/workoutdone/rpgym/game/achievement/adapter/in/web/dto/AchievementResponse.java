package com.workoutdone.rpgym.game.achievement.adapter.in.web.dto;

import com.workoutdone.rpgym.game.achievement.application.AchievementView;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;

import java.time.Instant;
import java.util.UUID;

public record AchievementResponse(
        UUID achievementId,
        String code,
        String name,
        String description,
        ConditionType conditionType,
        int conditionValue,
        int rewardXp,
        int currentValue,
        boolean achieved,
        Instant achievedAt
) {
    public static AchievementResponse from(AchievementView v) {
        return new AchievementResponse(
                v.achievementId(), v.code(), v.name(), v.description(),
                v.conditionType(), v.conditionValue(), v.rewardXp(),
                v.currentValue(), v.achieved(), v.achievedAt()
        );
    }
}
