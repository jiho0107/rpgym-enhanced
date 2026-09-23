package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AchievementQueryService {

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;

    /** 유저가 딸 수 있는 업적 전체(개인 · 파티, INACTIVE 포함)와 내 진행도. code 순 */
    @Transactional(readOnly = true)
    public List<AchievementView> findMine(UUID userId) {
        Map<UUID, UserAchievement> mine = userAchievementRepository.findAllByOwner(OwnerType.USER, userId).stream()
                .collect(Collectors.toMap(UserAchievement::getAchievementId, Function.identity()));

        return achievementRepository.findAllOrderByCode().stream()
                .map(a -> AchievementView.of(a, mine.get(a.getId())))
                .toList();
    }
}
