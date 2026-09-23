package com.workoutdone.rpgym.health.activity.domain;

/**
 * 건강 데이터 출처.
 *
 * 외부 요청(앱 → 게이트웨이 → /sync)으로 받을 수 있는 출처와
 * 서버 내부에서만 생성되는 출처를 구분한다.
 * 수집 채널이 달라도 모두 HealthActivitySyncUseCase 하나로 들어오며,
 * 출처는 저장·이벤트 흐름을 바꾸지 않고 기록 용도로만 쓴다.
 */
public enum ActivitySource {

    /** 서버 내부 Synthetic 수집기가 생성한 데이터. 외부 요청으로는 받지 않는다. */
    SYNTHETIC(false),

    /**
     * Samsung Health Data SDK 직접 연동용 예약 값.
     * Health Connect 표준 데이터로 부족한 삼성 고유 데이터가 필요해지면 사용한다.
     * 아직 연동하지 않았으므로 외부 요청으로는 받지 않는다.
     */
    SAMSUNG_HEALTH(false),

    /** Android Health Connect를 거쳐 앱이 보낸 데이터. 기본 외부 수집 채널이다. */
    HEALTH_CONNECT(true);

    private final boolean external;

    ActivitySource(boolean external) {
        this.external = external;
    }

    /** 외부 요청(POST /sync)으로 받을 수 있는 출처인지 */
    public boolean isExternal() {
        return external;
    }
}