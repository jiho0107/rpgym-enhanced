package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.CountResult;
import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;
import com.workoutdone.rpgym.game.party.domain.repo.PartyMemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 파티 종료 한 건을 완주자 전원의 파티 업적에 반영한다.
 *
 * 개인 업적(AchievementProgressService) 과 다른 점 하나 -- 세지 않고 다시 읽는다.
 * 완주 기록의 원본은 party_members 다. 이벤트가 오면 "이 유저가 완주한 파티 수" 를 원본에서
 * 다시 세어 current_value 에 절대값으로 덮어쓴다. 이벤트가 두 번 오든 뒤집혀 오든 같은 값이라
 * 중복 방지 테이블이 필요 없다. 랭킹이 XpGranted 마다 wallets 를 다시 읽는 것과 같은 규약이다.
 *
 * party 도메인의 리포지토리를 읽는다. 둘 다 같은 담당의 컨텍스트라 허용했고, 의존은 achievement → party
 * 한 방향이다. party 는 업적을 모른다 (PartyEnded 를 쏠 뿐).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyAchievementService {

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;
    private final PartyMemberRepository partyMemberRepository;

    /**
     * @param endedAt 파티의 ends_at. 완주자 판정 키(left_at = ends_at)이자 achieved_at 의 재료
     * @return 유저별 새로 딴 업적. 비어 있으면 진행만 됐거나 전부 SKIPPED
     */
    // REQUIRES_NEW 다. AFTER_COMMIT 리스너가 부르는데 그 시점엔 이미 커밋된 바깥 트랜잭션 리소스가
    // 아직 바인딩돼 있어, 기본 전파면 거기 합류해 save 가 조용히 유실된다.
    // 경계가 리스너가 아니라 여기인 이유는 리스너 주석 참고 (UnexpectedRollbackException).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<UUID, List<Achievement>> recordPartyCompleted(UUID partyId, Instant endedAt) {
        List<Achievement> targets = achievementRepository.findActiveByConditionTypes(ConditionType.PARTY);
        if (targets.isEmpty()) {
            log.warn("ACTIVE 파티 업적이 없다. V8 시드가 안 들어갔을 수 있다. partyId={}", partyId);
            return Map.of();
        }

        List<UUID> completers = partyMemberRepository.findUserIdsCompletedAt(partyId, endedAt);
        if (completers.isEmpty()) {
            // endParty 가 멤버 전원을 LEFT 로 바꾼 뒤 이벤트를 쏘므로 정상이면 비지 않는다.
            log.warn("파티 완주자가 없다. partyId={} endedAt={}", partyId, endedAt);
            return Map.of();
        }

        Map<UUID, List<Achievement>> unlockedByUser = new LinkedHashMap<>();
        for (UUID userId : completers) {
            int completed = (int) partyMemberRepository.countCompletedByUserId(userId);
            List<Achievement> unlocked = apply(userId, completed, targets, endedAt);
            if (!unlocked.isEmpty()) {
                unlockedByUser.put(userId, unlocked);
            }
        }
        return unlockedByUser;
    }

    private List<Achievement> apply(UUID userId, int completedCount, List<Achievement> targets, Instant endedAt) {
        Map<UUID, UserAchievement> mine = userAchievementRepository.findAllByOwner(OwnerType.USER, userId).stream()
                .collect(Collectors.toMap(UserAchievement::getAchievementId, Function.identity()));

        List<Achievement> newlyAchieved = new ArrayList<>();
        for (Achievement achievement : targets) {
            UserAchievement progress = mine.get(achievement.getId());
            if (progress == null) {
                progress = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, userId, achievement.getId());
            }

            CountResult result = progress.applyAbsolute(completedCount, achievement.getConditionValue(), endedAt);
            if (result == CountResult.SKIPPED) {
                log.debug("party achievement skipped: code={} userId={} completed={}",
                        achievement.getCode(), userId, completedCount);
                continue;
            }
            userAchievementRepository.save(progress);

            if (result == CountResult.ACHIEVED) {
                newlyAchieved.add(achievement);
                log.info("party achievement unlocked: code={} userId={} completed={} endedAt={}",
                        achievement.getCode(), userId, completedCount, endedAt);
            } else {
                log.debug("party achievement progressed: code={} userId={} current={}/{}",
                        achievement.getCode(), userId, progress.getCurrentValue(), achievement.getConditionValue());
            }
        }
        return newlyAchieved;
    }
}
