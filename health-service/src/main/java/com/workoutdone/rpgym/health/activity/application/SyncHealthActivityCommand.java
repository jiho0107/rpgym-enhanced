package com.workoutdone.rpgym.health.activity.application;

import com.workoutdone.rpgym.health.activity.domain.ActivitySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** web dto가 application 계층으로 새어 들어오지 않도록 하는 입력 모델
 * application 패키지 안의 어떤 파일도 adapter.in.web을 import하지 않는다.
 * web DTO는 컨트롤러에서 Command로 번역되고 거기서 멈춘다.
 */
public record SyncHealthActivityCommand(
        UUID userId,
        Instant measuredAt,
        int steps,
        int activeMinutes,
        int activeCalories,
        ActivitySource source
) {
    /**
     * measuredAt을 초 단위로 절삭한다.
     *
     * measuredAt은 (user_id, measured_at) 유니크 키의 일부라, 같은 측정이 다른 정밀도
     * (예: 01:30:00.789Z / 01:30:00Z)로 들어오면 서로 다른 행이 된다.
     * 어댑터마다 절삭하지 않고 여기서 한 번에 맞춰, 수집 채널과 관계없이 같은 기준을 갖게 한다.
     * 재전송 멱등성 자체는 클라이언트가 재시도 시 같은 measuredAt을 보내는 것을 전제로 한다.
     */
    public SyncHealthActivityCommand {
        if (measuredAt != null) {
            measuredAt = measuredAt.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}