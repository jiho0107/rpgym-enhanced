package com.workoutdone.rpgym.game.achievement.adapter.out.persistence;

import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface UserAchievementJpaRepository extends Repository<UserAchievement, UUID> {

    UserAchievement save(UserAchievement userAchievement);

    List<UserAchievement> findAllByOwnerTypeAndOwnerId(OwnerType ownerType, UUID ownerId);
}
