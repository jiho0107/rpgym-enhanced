package com.workoutdone.rpgym.game.achievement.domain;

/**
 * 하루치를 세어 본 결과. 서비스는 SKIPPED 면 저장하지 않고, ACHIEVED 만 "새로 딴 업적" 으로 돌려준다.
 */
public enum CountResult {
    /** 이미 센 날짜거나 과거 날짜라 아무것도 바꾸지 않았다 */
    SKIPPED,
    /** 카운터가 올라갔지만 아직 기준값 미만 */
    PROGRESSED,
    /** 이번에 기준값에 도달했다. 이 결과는 행당 평생 1번만 나온다 */
    ACHIEVED
}
