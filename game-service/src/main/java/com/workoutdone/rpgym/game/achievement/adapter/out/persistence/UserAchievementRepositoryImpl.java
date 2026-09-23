package com.workoutdone.rpgym.game.achievement.adapter.out.persistence;

import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserAchievementRepositoryImpl implements UserAchievementRepository {

    private final UserAchievementJpaRepository userAchievementJpaRepository;

    @Override
    public UserAchievement save(UserAchievement userAchievement) {
        return userAchievementJpaRepository.save(userAchievement);
    }

    @Override
    public List<UserAchievement> findAllByOwner(OwnerType ownerType, UUID ownerId) {
        return userAchievementJpaRepository.findAllByOwnerTypeAndOwnerId(ownerType, ownerId);
    }
}
