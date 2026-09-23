package com.workoutdone.rpgym.game.achievement.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedUpdatedEntity;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.CountResult;
import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.UserAchievementStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * owner(유저 or 파티) 의 업적 1개에 대한 진행 카운터이자 획득 기록.
 * (owner_type, owner_id, achievement_id) 당 1행 -- uk_user_achievements_owner_achievement.
 *
 * 멱등의 핵심은 last_counted_date 다. Health 가 같은 날 DAILY_GOAL_COMPLETED 를 두 번 보내도
 * (재전송, 컨슈머 재시도) 두 번째는 SKIPPED 로 끝난다. dedupKey 는 Health 쪽 방어고 이건 내 쪽 방어다.
 *
 * (날짜 기반 조건만) 과거 날짜(activityDate < last_counted_date) 도 SKIPPED 다. 순서가 뒤집힌 이벤트를 세면
 * STREAK 가 1로 리셋되어 이미 쌓은 연속을 잃는다. 하루 1건 규칙 아래에서 과거 날짜는
 * 이미 셌거나(중복) 놓친 날(재전송 불가) 둘 중 하나라 세지 않는 편이 안전하다.
 */
@Entity
@Getter
@Table(name = "user_achievements", schema = "game_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAchievement extends BaseCreatedUpdatedEntity {

    @Id
    @Column(name = "user_achievement_id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10, updatable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "achievement_id", nullable = false, updatable = false)
    private UUID achievementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserAchievementStatus status;

    @Column(name = "current_value", nullable = false)
    private int currentValue;

    @Column(name = "last_counted_date")
    private LocalDate lastCountedDate;

    @Column(name = "achieved_at")
    private Instant achievedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static UserAchievement start(UUID id, OwnerType ownerType, UUID ownerId, UUID achievementId) {
        UserAchievement ua = new UserAchievement();
        ua.id = id;
        ua.ownerType = ownerType;
        ua.ownerId = ownerId;
        ua.achievementId = achievementId;
        ua.status = UserAchievementStatus.IN_PROGRESS;
        ua.currentValue = 0;
        return ua;
    }

    /**
     * 날짜 기반 조건(DAILY_*) 의 하루치를 센다.
     *
     * @param conditionType  세는 방식 (누적 / 연속). DAILY_* 만 받는다
     * @param conditionValue 이 값에 닿으면 ACHIEVED
     * @param achievedAt     Health 가 준 달성 시각. 내 서버 시계가 아니라 이 값을 기록해야
     *                       재처리해도 같은 값이 남는다
     */
    public CountResult count(LocalDate activityDate, ConditionType conditionType, int conditionValue,
                             Instant achievedAt) {
        if (!ConditionType.DAILY_GOAL.contains(conditionType)) {
            // 날짜로 멱등을 잡는 메서드다. 원천 기반 조건이 여기 오면 서비스가 조건을 잘못 골라 온 것이다.
            throw new IllegalArgumentException("날짜 기반 조건이 아니다: " + conditionType);
        }
        if (status == UserAchievementStatus.ACHIEVED) {
            return CountResult.SKIPPED;
        }
        if (lastCountedDate != null && !activityDate.isAfter(lastCountedDate)) {
            return CountResult.SKIPPED;
        }

        currentValue = switch (conditionType) {
            case DAILY_GOAL_COUNT -> currentValue + 1;
            case DAILY_GOAL_STREAK -> isConsecutive(activityDate) ? currentValue + 1 : 1;
            case PARTY_COMPLETED_COUNT -> throw new IllegalStateException("unreachable");
        };
        lastCountedDate = activityDate;

        return settle(conditionValue, achievedAt);
    }

    /**
     * 원천 기반 조건(PARTY_*) 의 현재값을 절대값으로 덮어쓴다.
     *
     * 원본(party_members) 에서 다시 센 값을 그대로 받는다. 그래서 중복 · 순서 역전 검사가 없다 --
     * 두 번 오든 뒤집혀 오든 원본을 다시 세면 같은 값이다. 랭킹이 wallets 를 다시 읽는 것과 같은 규약.
     *
     * @param absoluteValue 원본에서 센 값 (예: 완주한 파티 수)
     * @param countedAt     원천이 확정된 시각 (파티면 ends_at). last_counted_date 와 achieved_at 의 재료
     */
    public CountResult applyAbsolute(int absoluteValue, int conditionValue, Instant countedAt) {
        if (absoluteValue < 0) {
            throw new IllegalArgumentException("absoluteValue must be >= 0 but was " + absoluteValue);
        }
        if (status == UserAchievementStatus.ACHIEVED) {
            return CountResult.SKIPPED;
        }
        if (absoluteValue == currentValue) {
            return CountResult.SKIPPED;   // 다시 셌더니 그대로다 (재전송)
        }
        currentValue = absoluteValue;
        lastCountedDate = LocalDate.ofInstant(countedAt, ZoneOffset.UTC);
        return settle(conditionValue, countedAt);
    }

    private CountResult settle(int conditionValue, Instant achievedAt) {
        if (currentValue >= conditionValue) {
            status = UserAchievementStatus.ACHIEVED;
            this.achievedAt = achievedAt;
            return CountResult.ACHIEVED;
        }
        return CountResult.PROGRESSED;
    }

    private boolean isConsecutive(LocalDate activityDate) {
        return lastCountedDate != null && lastCountedDate.plusDays(1).equals(activityDate);
    }

    public boolean isAchieved() {
        return status == UserAchievementStatus.ACHIEVED;
    }
}
