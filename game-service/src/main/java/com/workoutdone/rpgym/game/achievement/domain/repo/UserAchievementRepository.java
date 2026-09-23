package com.workoutdone.rpgym.game.achievement.domain.repo;

import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;

import java.util.List;
import java.util.UUID;

public interface UserAchievementRepository {

    UserAchievement save(UserAchievement userAchievement);

    List<UserAchievement> findAllByOwner(OwnerType ownerType, UUID ownerId);
}
