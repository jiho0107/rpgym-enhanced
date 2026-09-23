package com.workoutdone.rpgym.game.achievement.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DAILY_GOAL_COMPLETED 의 data. Health 가 일일 목표 3종을 그날 처음 전부 달성한 순간 1회 발행한다 (#125).
 *
 * userId 는 envelope 에 있다. bonusXp 같은 보상 필드는 없다 -- 보상 여부는 Game 이 정하고, 지금은 주지 않는다.
 * summaryId 는 지금 쓰지 않지만 Health 의 daily_health_summaries 행을 가리키는 추적용 키라 받아 둔다.
 * 판정 키는 activityDate 다. (userId, activityDate) 가 Health 쪽 dedupKey 이자 내 쪽 last_counted_date 다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyGoalCompletedData(
        UUID summaryId,
        LocalDate activityDate,
        OffsetDateTime achievedAt
) {
}
