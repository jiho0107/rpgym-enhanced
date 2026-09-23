package com.workoutdone.rpgym.game.achievement.adapter.out.persistence;

import com.workoutdone.rpgym.game.achievement.domain.AchievementStatus;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AchievementJpaRepository extends Repository<Achievement, UUID> {

    List<Achievement> findAllByConditionTypeInAndStatusOrderByCodeAsc(
            Collection<ConditionType> conditionTypes, AchievementStatus status);

    List<Achievement> findAllByOrderByCodeAsc();
}
