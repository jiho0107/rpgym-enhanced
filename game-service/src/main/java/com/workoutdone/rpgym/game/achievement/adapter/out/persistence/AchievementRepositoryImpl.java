package com.workoutdone.rpgym.game.achievement.adapter.out.persistence;

import com.workoutdone.rpgym.game.achievement.domain.AchievementStatus;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AchievementRepositoryImpl implements AchievementRepository {

    private final AchievementJpaRepository achievementJpaRepository;

    @Override
    public List<Achievement> findActiveByConditionTypes(Collection<ConditionType> conditionTypes) {
        return achievementJpaRepository.findAllByConditionTypeInAndStatusOrderByCodeAsc(
                conditionTypes, AchievementStatus.ACTIVE);
    }

    @Override
    public List<Achievement> findAllOrderByCode() {
        return achievementJpaRepository.findAllByOrderByCodeAsc();
    }
}
