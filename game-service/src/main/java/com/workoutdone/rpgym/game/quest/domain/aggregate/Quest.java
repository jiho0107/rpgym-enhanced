package com.workoutdone.rpgym.game.quest.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedUpdatedEntity;
import com.workoutdone.rpgym.game.quest.domain.vo.ApplyResult;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.QuestStatus;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;

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
import java.util.UUID;

@Entity
@Getter
@Table(name = "quests", schema = "game_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Quest extends BaseCreatedUpdatedEntity {

    @Id
    @Column(name = "quest_id", nullable = false, updatable = false)
    private UUID questId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // uk_quests_suggestion UNIQUE
    @Column(name = "suggestion_id", nullable = false, updatable = false, unique = true)
    private UUID suggestionId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 20, updatable = false)
    private Metric metric;

    @Column(name = "target_val", nullable = false, updatable = false)
    private int targetVal;

    @Column(name = "baseline_val", nullable = false, updatable = false)
    private int baselineVal;

    @Column(name = "baseline_measured_at", nullable = false, updatable = false)
    private Instant baselineMeasuredAt;

    @Column(name = "last_applied_measured_at")
    private Instant lastAppliedMeasuredAt;

    @Column(name = "last_cumulative_val")
    private Integer lastCumulativeVal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuestStatus status;

    /**
     * 완료 시 지급할 XP. 보상 정책 상수가 정한 값의 스냅샷이다.
     * 정책이 바뀌어도 이미 생성된 Quest의 보상은 불변이다.
     * 금액을 Health(AI)가 정하지 못하게해야함
     */
    @Column(name = "reward_xp", nullable = false, updatable = false)
    private int rewardXp;

    @Column(name = "expired_at", nullable = false, updatable = false)
    private Instant expiredAt;

    /**
     * 낙관적 락. 파티션 키가 userId라 같은 유저 이벤트는 한 컨슈머에서 순서대로 처리되지만,
     * 컨슈머 그룹 리밸런싱 중에는 같은 파티션이 잠깐 두 인스턴스에 겹칠 수 있다.
     * 그 창에서의 lost update만 막는 용도이며, 중복/순서 판정 자체는 아래 applySnapshot이 한다.
     *
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * QuestSuggested 제안을 수용해 Quest를 생성한다.
     *
     * 호출 전에 애플리케이션 계층이 확인해야 하는 것 (여기서 하지 않는다 — DB 조회가 필요하므로):
     */
    public static Quest create(
            UUID questId,
            UUID userId,
            UUID suggestionId,
            String title,
            Metric metric,
            int targetVal,
            int baselineVal,
            Instant baselineMeasuredAt,
            int rewardXp,
            Instant expiredAt
    ) {
        // targetVal / baselineVal의 출처는 Health의 AI다.
        // 셋 다 "값이 이상하다"가 아니라 "그대로 두면 xp가 나가거나 전체 기능이 멈추는것과 같다"
        // 인 것만 골랐다.

        // targetVal <= 0 이면 첫 스냅샷에서 달성분 0 >= 0 이 성립해 즉시 완료 → 공짜 XP 지급.
        if (targetVal <= 0) {
            throw new IllegalArgumentException("targetVal must be positive but was " + targetVal);
        }
        // baselineVal이 음수면 달성분이 부풀려져 목표를 조기 달성한다. 리워드에 문제가 생김.
        if (baselineVal < 0) {
            throw new IllegalArgumentException("baselineVal must not be negative but was " + baselineVal);
        }
        // 태어날 때부터 만료된 Quest는 모든 스냅샷이 AFTER_EXPIRY로 걸러져 영원히 완료되지 않는다.
        if (!expiredAt.isAfter(baselineMeasuredAt)) {
            throw new IllegalArgumentException(
                    "expiredAt must be after baselineMeasuredAt: " + expiredAt + " <= " + baselineMeasuredAt);
        }

        Quest quest = new Quest();
        quest.questId = questId;
        quest.userId = userId;
        quest.suggestionId = suggestionId;
        quest.title = title;
        quest.metric = metric;
        quest.targetVal = targetVal;
        quest.baselineVal = baselineVal;
        quest.baselineMeasuredAt = baselineMeasuredAt;
        quest.rewardXp = rewardXp;
        quest.expiredAt = expiredAt;
        quest.status = QuestStatus.ACTIVE;
        quest.lastAppliedMeasuredAt = null; // 첫 이벤트 전
        quest.lastCumulativeVal = null;
        return quest;
    }

    /**
     * 건강 데이터 스냅샷 하나를 이 Quest에 반영하고 판정한다. 가장 중요한 부분
     * 순서가 곧 우선순위다
     * snapshot 이 Quest의 유저·날짜에 해당하는 누적 스냅샷 (검증은 호출부 책임)
     * return 반영 결과. {Completed}일 때만 XP 지급과 QuestCompleted 발행이 일어난다
     */
    public ApplyResult applySnapshot(Snapshot snapshot) {
        // 1. 이미 끝난 Quest에는 아무것도 반영하지 않는다
        // 완료된 Quest에 이벤트가 더 들어와도 여기서 끊긴다. 보상은 한 번뿐이다.
        if (status != QuestStatus.ACTIVE) {
            return new ApplyResult.Ignored(ApplyResult.Reason.NOT_ACTIVE);
        }

        // Instant.now()가 아니라 snapshot.measuredAt()인 이유
        // 도메인 영역은 시계(외부어댑터)에도 의존성을 가지면 안되기 때문
        Instant measuredAt = snapshot.measuredAt();

        // 2. 중복 도착과 순서 역전을 한 조건으로 막는다(이미 본 시각 이하의 스냅샷)
        // !isAfter(x) 는 "x 이하"다. '미만'으로 쓰면 같은 measuredAt 재수신이 통과하는데,
        // 중복이란 정확히 그 경우다. 여기가 뚫리면 XP가 두 번 지급된다.
        // lastAppliedMeasuredAt이 null이면 첫 이벤트이므로 baseline 시각과 비교한다.
        // 비교 없이 통과시키면 수락 직후 늦게 온 어제 이벤트가 어제 누적값 - 오늘 baseline 으로
        // 달성분을 만들어 공짜 완료가 난다. baseline 은 수락 시 오늘 스냅샷인지 대조했으므로
        // 어제 이벤트는 항상 baseline 시각보다 이르다.
        // 게임서비스의 정합성은 이 워터마크가 책임 진다고해도 과언이 아니다.
        // 마지막으로 딱 한문 장 정리하자면 어제온 퀘스트가 올때 원래 코드는 마지막에 쟀던 데이터가
        // null이면 통과였음. 근데 생각해보니 하루치 퀘스트만 적용되는데 23:59:59초에 유저가 수락을 눌러서
        // 퀘스트 수락이 00:00분에 되면 어제 활동분 기준으로 퀘스트 판정이 됨
        // 그럼 오늘에 이미 N보 걸은 셈이되니깐 공짜 xp가 나감
        // 그걸 워터마크로
        Instant watermark = lastAppliedMeasuredAt != null ? lastAppliedMeasuredAt : baselineMeasuredAt;
        if (!measuredAt.isAfter(watermark)) {
            return new ApplyResult.Ignored(ApplyResult.Reason.STALE_SNAPSHOT);
        }

        // 3. 만료 판정
        // Instant.now()가 아니라 measuredAt으로 판정한다. 컨슈머가 죽었다 자정 넘어
        // 살아나도, 만료 전에 목표를 채운 유저는 보상을 받아야 한다.
        // 같은 이벤트를 언제 처리하든 결과가 같아야 멱등이다.
        // 유효 구간은 [baseline, expiredAt) 이다.
        // 퀘스트만 다른 파티퀘스트와 달리 조회 기준이 달라서 통일함
        if (!measuredAt.isBefore(expiredAt)) {
            return new ApplyResult.Ignored(ApplyResult.Reason.AFTER_EXPIRY);
        }

        // 4. 달성분 계산
        // Quest 수락 시점의 누적값을 빼야 아침에 이미 걸어둔 활동이 소급 인정되지 않는다.
        int cumulative = snapshot.valueOf(metric);
        int achievedDelta = cumulative - baselineVal;

        // 음수 = 자정 리셋이거나 Health 측 데이터 정정. 진행도를 롤백하지 않고 무시한다.
        // 다음 스냅샷이 누적값을 다시 실어오므로 최종값은 알아서 맞는다.
        if (achievedDelta < 0) {
            return new ApplyResult.Ignored(ApplyResult.Reason.NEGATIVE_DELTA);
        }

        // 5. 상태 갱신
        // 대입이다. += 가 아니다. 구간 증분을 더해 나가면 이벤트 하나가 유실될 때
        // 그만큼이 영구히 사라진다. 누적값을 덮어쓰면 다음 스냅샷이 자동으로 메운다.
        this.lastCumulativeVal = cumulative;
        this.lastAppliedMeasuredAt = measuredAt;

        // 6. 완료 판정
        // Completed를 돌려준 이 호출에서만 XP 지급과 QuestCompleted 발행이 일어난다.
        // 그 둘은 여기서 하지 않는다 — 결과를 받은 애플리케이션 계층의 일이다.
        if (achievedDelta >= targetVal) {
            this.status = QuestStatus.COMPLETED;
            return new ApplyResult.Completed(achievedDelta);
        }
        return new ApplyResult.Progressed(achievedDelta);
    }


    public int progress() {
        if (lastCumulativeVal == null) {
            return 0;
        }
        return lastCumulativeVal - baselineVal;
    }


    public QuestStatus displayStatus(Instant at) {
        if (status == QuestStatus.ACTIVE && !at.isBefore(expiredAt)) {
            return QuestStatus.EXPIRED;
        }
        return status;
    }
}
