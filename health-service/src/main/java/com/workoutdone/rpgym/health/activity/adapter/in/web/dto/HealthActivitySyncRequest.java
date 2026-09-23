package com.workoutdone.rpgym.health.activity.adapter.in.web.dto;

import com.workoutdone.rpgym.health.activity.adapter.in.web.validation.ExternalActivitySource;
import com.workoutdone.rpgym.health.activity.adapter.in.web.validation.NotFutureMeasuredAt;
import com.workoutdone.rpgym.health.activity.application.SyncHealthActivityCommand;
import com.workoutdone.rpgym.health.activity.domain.ActivitySource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

/**
 * 건강 활동 동기화 요청 (외부 수집 채널: 앱 → 게이트웨이 → 이 API).
 * 세 지표는 증분이 아니라 해당 시점까지의 당일 누적값이다.
 */
public record HealthActivitySyncRequest(

        @NotNull(message = "측정 시점은 필수입니다.")
        @NotFutureMeasuredAt
        Instant measuredAt,

        @NotNull(message = "걸음 수는 필수입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer steps,

        @NotNull(message = "활동 시간은 필수입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer activeMinutes,

        @NotNull(message = "활동 칼로리는 필수입니다.")
        @PositiveOrZero(message = "0 이상이어야 합니다.")
        Integer activeCalories,

        @NotNull(message = "데이터 출처는 필수입니다.")
        @ExternalActivitySource
        ActivitySource source
) {
    public SyncHealthActivityCommand toCommand(UUID userId) {
        return new SyncHealthActivityCommand(
                userId, measuredAt, steps, activeMinutes, activeCalories, source);
    }
}