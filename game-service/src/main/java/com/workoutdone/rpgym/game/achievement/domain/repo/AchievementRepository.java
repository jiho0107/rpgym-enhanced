package com.workoutdone.rpgym.game.achievement.domain.repo;

import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;

import java.util.Collection;
import java.util.List;

public interface AchievementRepository {

    /**
     * 지금 세야 하는 업적. status = ACTIVE 이고 조건 종류가 주어진 집합 안.
     * 입력 이벤트마다 셀 수 있는 조건이 다르다 -- 일일 목표 이벤트는 DAILY_*, 파티 종료는 PARTY_*.
     * scope 가 아니라 조건 종류로 고른다. idx_achievements_condition(condition_type, status) 를 탄다.
     */
    List<Achievement> findActiveByConditionTypes(Collection<ConditionType> conditionTypes);

    /** 목록 화면용. INACTIVE 도 포함 -- 이미 딴 기록은 보여야 한다. sort_order 는 V6 에 없어 code 순 */
    List<Achievement> findAllOrderByCode();
}
