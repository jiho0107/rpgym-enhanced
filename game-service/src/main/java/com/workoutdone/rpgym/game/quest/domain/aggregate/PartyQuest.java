package com.workoutdone.rpgym.game.quest.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedUpdatedEntity;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.QuestStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

// 파티 하나가 함께 채우는 퀘스트다.
// 개인 quests 테이블에 얹지 않은 이유는 기준값이 멤버마다 다르기 때문이다.
// quests.baseline_val 컬럼 하나에 넣을 값이 없다. 비어 있어도 되게 바꾸는 순간
// "진행 중인 퀘스트는 기준이 확정돼 있다" 는 성질이 깨지고 판정 코드에 분기가 생긴다.
// 기간은 최대 하루다. 건강 데이터는 자정마다 0 으로 돌아가는 당일 누적값이고
// 어제 값을 보관하는 테이블이 어디에도 없어서, 며칠짜리로 만들면 날짜별 증분을
// 따로 쌓아야 한다. 하루짜리면 개인 퀘스트와 똑같이 "누적값 빼기 기준값" 한 줄로 끝난다.
// 상태와 진행값을 바꾸는 메서드가 여기 없다.
// 그 둘은 여러 트랜잭션이 동시에 건드리는 값이라 조건부 UPDATE 문으로만 갱신해야 한다.
// 엔티티를 읽어서 필드를 바꾸고 저장하는 방식으로 하면,
// 읽는 시점과 쓰는 시점 사이에 들어온 다른 트랜잭션의 갱신을 덮어쓴다.
// 그래서 그 두 갱신은 리포지토리에 조건부 UPDATE 로 있다.
@Entity
@Getter
@Table(name = "party_quests", schema = "game_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyQuest extends BaseCreatedUpdatedEntity {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Id
    @Column(name = "party_quest_id", nullable = false, updatable = false)
    private UUID partyQuestId;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 20, updatable = false)
    private Metric metric;

    // 멤버들의 기여분 합계 목표다.
    @Column(name = "target_val", nullable = false, updatable = false)
    private int targetVal;

    // 멤버 기여분의 합계. 경합 지점이다.
    // 완료된 뒤에 도착한 기여도 여기 그대로 쌓여서 목표를 넘길 수 있다.
    // 그것이 의도한 동작이다. 여기에 상태 조건을 걸면 완료 직후 도착한 이벤트에서
    // 멤버 행만 갱신되고 합계가 안 올라가, 둘이 같아야 한다는 등식이 깨진다.
    // 그러면 정합성 배치가 그 차이를 진짜 문제로 신고하고,
    // 진짜 문제가 섞여도 구분할 수 없게 된다.
    @Column(name = "current_val", nullable = false)
    private int currentVal;

    // 완료를 선점하는 조건 컬럼이다. 완료 여부는 이 컬럼을 건 조건부 UPDATE 가 단독으로 결정한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuestStatus status;

    // 완료 시 멤버 한 사람당 지급할 XP 다. 생성 시점 정책값의 스냅샷이라 나중에 정책이 바뀌어도 변하지 않는다.
    @Column(name = "reward_xp", nullable = false, updatable = false)
    private int rewardXp;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expired_at", nullable = false, updatable = false)
    private Instant expiredAt;

    public static PartyQuest create(
            UUID partyQuestId,
            UUID partyId,
            String title,
            Metric metric,
            int targetVal,
            int rewardXp,
            Instant startedAt,
            Instant expiredAt
    ) {
        // 목표가 0 이하면 첫 스냅샷에서 기여 0 이 목표 이상이 되어 즉시 완료된다.
        // 멤버 전원에게 XP 가 공짜로 나간다.
        if (targetVal <= 0) {
            throw new IllegalArgumentException("targetVal must be positive but was " + targetVal);
        }
        if (rewardXp <= 0) {
            throw new IllegalArgumentException("rewardXp must be positive but was " + rewardXp);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        // 태어날 때부터 만료된 퀘스트는 모든 이벤트가 조회에서 걸러져 영원히 완료되지 않는다.
        if (!expiredAt.isAfter(startedAt)) {
            throw new IllegalArgumentException(
                    "expiredAt must be after startedAt: " + expiredAt + " <= " + startedAt);
        }

        PartyQuest partyQuest = new PartyQuest();
        partyQuest.partyQuestId = partyQuestId;
        partyQuest.partyId = partyId;
        partyQuest.title = title;
        partyQuest.metric = metric;
        partyQuest.targetVal = targetVal;
        partyQuest.currentVal = 0;
        partyQuest.status = QuestStatus.ACTIVE;
        partyQuest.rewardXp = rewardXp;
        partyQuest.startedAt = startedAt;
        partyQuest.expiredAt = expiredAt;
        return partyQuest;
    }

    // 하루치 퀘스트기 때문에 기한이 그날 한국 시간 자정 직전에 대한 반환.
    // 컬럼을 따로 두지 않는다. 이미 expiredAt이 존재하기 때문에 칼럼의 값은
    // 고정하고 expiredAt 값을 읽어서 한국 시간 날짜 즉 LocalDate를 반환한다.
    public LocalDate activityDate() {
        return expiredAt.atZone(KST).toLocalDate();
    }
}
