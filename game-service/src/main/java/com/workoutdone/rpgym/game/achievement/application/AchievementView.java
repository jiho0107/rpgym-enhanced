package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;

import java.time.Instant;
import java.util.UUID;

/**
 * 업적 정의 + 내 진행도를 합친 조회 결과. 진행 행이 없으면 0/미달성으로 채운다 --
 * "아직 시작 안 함" 도 목록에 보여야 다음 목표가 보인다.
 */
public record AchievementView(
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
    public static AchievementView of(Achievement achievement, UserAchievement progress) {
        return new AchievementView(
                achievement.getId(),
                achievement.getCode(),
                achievement.getName(),
                achievement.getDescription(),
                achievement.getConditionType(),
                achievement.getConditionValue(),
                achievement.getRewardXp(),
                progress == null ? 0 : progress.getCurrentValue(),
                progress != null && progress.isAchieved(),
                progress == null ? null : progress.getAchievedAt()
        );
    }
}
