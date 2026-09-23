package com.workoutdone.rpgym.game.achievement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 업적 달성 조건. V6 · V8 의 ck_achievements_condition_type 과 같은 값이어야 한다.
 *
 * 두 갈래로 나뉜다.
 *   날짜 기반 (DAILY_*)   : 입력이 "이 유저가 이 날짜에 달성했다". 같은 날은 한 번만 센다 -- last_counted_date.
 *   원천 기반 (PARTY_*)   : 입력이 "이 유저가 이 파티를 완주했다". 같은 원천은 한 번만 센다 -- achievement_sources.
 * 같은 날 파티 두 개가 끝날 수 있어서 파티는 날짜로 못 막는다. 그래서 갈래가 둘이다.
 */
public enum ConditionType {
    /** 일일 목표 누적 달성 횟수. 최초 달성 = 1 */
    DAILY_GOAL_COUNT,
    /** 일일 목표 연속 달성 일수. 하루 빠지면 1부터 */
    DAILY_GOAL_STREAK,
    /** 파티 완주(ENDED 시점 멤버) 누적 횟수 */
    PARTY_COMPLETED_COUNT;

    public static final Set<ConditionType> DAILY_GOAL = EnumSet.of(DAILY_GOAL_COUNT, DAILY_GOAL_STREAK);
    public static final Set<ConditionType> PARTY = EnumSet.of(PARTY_COMPLETED_COUNT);
}
