package com.workoutdone.rpgym.game.achievement.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedUpdatedEntity;
import com.workoutdone.rpgym.game.achievement.domain.AchievementScope;
import com.workoutdone.rpgym.game.achievement.domain.AchievementStatus;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 업적 정의. "어떤 업적이 있는가". V6 시드로 들어가고 코드는 읽기만 한다.
 *
 * 코드는 UUID 가 아니라 code 로 찾는다 -- 시드 UUID 는 gen_random_uuid() 라 환경마다 다르다.
 */
@Entity
@Getter
@Table(name = "achievements", schema = "game_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Achievement extends BaseCreatedUpdatedEntity {

    @Id
    @Column(name = "achievement_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private AchievementScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 30)
    private ConditionType conditionType;

    @Column(name = "condition_value", nullable = false)
    private int conditionValue;

    /** V6 컬럼. 업적 보상은 기획 미확정이라 지금은 읽어서 보여주기만 하고 지급하지 않는다 */
    @Column(name = "reward_xp", nullable = false)
    private int rewardXp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AchievementStatus status;

    /** 테스트 · 시드 보조용. 운영에서는 마이그레이션이 만든다. */
    public static Achievement create(UUID id, String code, String name, String description,
                                     AchievementScope scope, ConditionType conditionType,
                                     int conditionValue, int rewardXp) {
        if (conditionValue < 1) {
            throw new IllegalArgumentException("conditionValue must be >= 1 but was " + conditionValue);
        }
        if (rewardXp < 0) {
            throw new IllegalArgumentException("rewardXp must be >= 0 but was " + rewardXp);
        }
        Achievement a = new Achievement();
        a.id = id;
        a.code = code;
        a.name = name;
        a.description = description;
        a.scope = scope;
        a.conditionType = conditionType;
        a.conditionValue = conditionValue;
        a.rewardXp = rewardXp;
        a.status = AchievementStatus.ACTIVE;
        return a;
    }
}
