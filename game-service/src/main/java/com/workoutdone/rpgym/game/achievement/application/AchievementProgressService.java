package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.CountResult;
import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DAILY_GOAL_COMPLETED 한 건을 개인 업적 전부에 반영한다.
 *
 * 업적은 이력이다. "이 유저가 이 날짜에 달성했다" 를 세어 두는 것이 전부이고 XP 를 주지 않는다.
 * achievements.reward_xp 컬럼은 V6 에 있지만 여기서 읽지 않는다 -- 보상 여부는 기획 미확정이고,
 * 주기로 정해지면 그때 XpGrantService(퀘스트 담당 소유) 호출을 이 트랜잭션 안에 넣는다.
 *
 * 동시성: Health 는 userId 를 파티션 키로 쓰므로 같은 유저의 이벤트는 한 컨슈머가 순서대로 받는다.
 * 그래도 재시작 직후 같은 이벤트가 겹치면 (1) @Version 이 한쪽을 되돌리고
 * (2) 행 생성이 겹치면 uk_user_achievements_owner_achievement 가 막는다. 어느 쪽이든 재시도하면
 * 이미 센 날짜라 SKIPPED 로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AchievementProgressService {

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;

    /**
     * @return 이번 호출로 새로 딴 업적. 비어 있으면 진행만 됐거나 전부 SKIPPED
     */
    @Transactional
    public List<Achievement> recordDailyGoal(UUID userId, LocalDate activityDate, Instant achievedAt) {
        List<Achievement> targets = achievementRepository.findActiveByConditionTypes(ConditionType.DAILY_GOAL);
        if (targets.isEmpty()) {
            log.warn("ACTIVE 개인 업적이 없다. V6 시드가 안 들어갔을 수 있다. userId={}", userId);
            return List.of();
        }

        Map<UUID, UserAchievement> mine = userAchievementRepository.findAllByOwner(OwnerType.USER, userId).stream()
                .collect(Collectors.toMap(UserAchievement::getAchievementId, Function.identity()));

        List<Achievement> newlyAchieved = new ArrayList<>();
        for (Achievement achievement : targets) {
            UserAchievement progress = mine.get(achievement.getId());
            if (progress == null) {
                progress = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, userId, achievement.getId());
            }

            CountResult result = progress.count(
                    activityDate, achievement.getConditionType(), achievement.getConditionValue(), achievedAt);
            if (result == CountResult.SKIPPED) {
                // 변화가 없으니 저장하지 않는다. 새 행은 lastCountedDate 가 없어 SKIPPED 가 나올 수 없다.
                log.debug("achievement skipped: code={} userId={} activityDate={} lastCounted={}",
                        achievement.getCode(), userId, activityDate, progress.getLastCountedDate());
                continue;
            }
            userAchievementRepository.save(progress);

            if (result == CountResult.ACHIEVED) {
                newlyAchieved.add(achievement);
                log.info("achievement unlocked: code={} userId={} achievedAt={}",
                        achievement.getCode(), userId, achievedAt);
            } else {
                log.debug("achievement progressed: code={} userId={} current={}/{}",
                        achievement.getCode(), userId, progress.getCurrentValue(), achievement.getConditionValue());
            }
        }
        return newlyAchieved;
    }
}
