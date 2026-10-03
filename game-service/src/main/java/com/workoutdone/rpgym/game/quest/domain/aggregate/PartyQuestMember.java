package com.workoutdone.rpgym.game.quest.domain.aggregate;

import com.workoutdone.rpgym.common.entity.BaseCreatedUpdatedEntity;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.vo.ContributionResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 파티 퀘스트에 참여한 멤버 한 사람의 진행 상태다.
// 파티 담당자의 party_member 와 다른 테이블이다. 덮어쓰지 않는다.
//   party_member -> 이 유저가 이 파티에 있는가(파티당 한 행, 담당자 소유)
//   party_quest_members -> 이 유저가 이 퀘스트에 얼마나 쌓았나 (퀘스트당 한 행, 내 소유)
// 명단은 퀘스트를 만들 때 복사하고 그 뒤로는 party_member 를 읽지 않는다.
// 시작한 뒤에는 탈퇴도 중도 합류도 없다고 팀에서 정했기 때문에 명단이 변하지 않는다.
// 이 행은 경합이 없다. 카프카 파티션 키가 userId 라서 같은 유저의 이벤트는
// 한 컨슈머가 순서대로 처리한다. 그래서 읽고 계산해서 쓰는 방식을 써도 안전하고,
// 판정을 여기 도메인에 둘 수 있다. 경합이 있는 것은 공유 카운터 하나뿐이다.
@Entity
@Getter
@Table(name = "party_quest_members", schema = "game_service")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyQuestMember extends BaseCreatedUpdatedEntity {

    @Id
    @Column(name = "party_quest_member_id", nullable = false, updatable = false)
    private UUID partyQuestMemberId;

    @Column(name = "party_quest_id", nullable = false, updatable = false)
    private UUID partyQuestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // 이 멤버가 퀘스트 시작 시점에 이미 쌓아둔 누적값이다. 멤버마다 다르다.
    // 비어 있으면 아직 기준을 정하지 않았다는 뜻이다.
    // 한 번도 동기화한 적 없는 유저만 여기 해당한다. 그 멤버의 첫 이벤트가 도착할 때 확정한다.
    // 비어 있는 것을 0 으로 취급하면 안 된다.
    // 그러면 그 유저가 아침에 이미 걸어둔 활동이 통째로 기여로 잡혀서,
    // 목표가 작으면 퀘스트가 시작하자마자 완료되고 XP 가 공짜로 나간다.
    @Column(name = "baseline_val")
    private Integer baselineVal;

    @Column(name = "contributed_val", nullable = false)
    private int contributedVal;

    // 이 값 이하의 스냅샷은 중복이거나 순서가 뒤집힌 것이라 무시한다.
    @Column(name = "last_applied_measured_at")
    private Instant lastAppliedMeasuredAt;

    public static PartyQuestMember join(UUID partyQuestMemberId, UUID partyQuestId, UUID userId, Integer baselineVal) {
        if (baselineVal != null && baselineVal < 0) {
            throw new IllegalArgumentException("baselineVal must not be negative but was " + baselineVal);
        }
        PartyQuestMember member = new PartyQuestMember();
        member.partyQuestMemberId = partyQuestMemberId;
        member.partyQuestId = partyQuestId;
        member.userId = userId;
        member.baselineVal = baselineVal;
        member.contributedVal = 0;
        member.lastAppliedMeasuredAt = null;
        return member;
    }

    // 건강 데이터 스냅샷 하나를 이 멤버에게 반영한다.
    // 위에서 걸린 것은 아래를 보지 않는다.
    // dlq에서 이벤트 누락된게 재처리될때 여기서 막는다.
    public ContributionResult apply(Snapshot snapshot, Metric metric, LocalDate questDate) {
        Instant measuredAt = snapshot.measuredAt();

        // 0. 퀘스트 날짜의 스냅샷만 받는다.
        // 조회는 기한 이전인지만 보므로, 생성 뒤에 늦게 도착한 어제 이벤트도 여기까지 온다.
        // 워터마크가 비어 있는 동안에는 1 번은 어제날짜를 거르지 못한다.
        // 0번을 추가하지않으면 기준이 없는 멤버는 어제 누적값이 기준이 되어 오늘 기여가 계속 음수가 되고,
        // 기준이 있는 멤버는 어제 누적값 빼기 오늘 기준이 기여로 잡혀 공짜 완료가 난다.
        if (!snapshot.activityDate().equals(questDate)) {
            return new ContributionResult.Ignored(ContributionResult.Reason.OTHER_DAY);
        }

        // 1. 중복 도착과 순서 역전을 한 조건으로 막는다.
        // 이미 본 시각과 같은 것도 막아야 한다. 중복이란 정확히 그 경우다.
        // 처음이면 비교 없이 통과시킨다.
        if (lastAppliedMeasuredAt != null && !measuredAt.isAfter(lastAppliedMeasuredAt)) {
            return new ContributionResult.Ignored(ContributionResult.Reason.STALE_SNAPSHOT);
        }

        int cumulative = snapshot.valueOf(metric);

        // 2. 기준이 아직 없으면 이번 누적값으로 확정만 하고 기여는 잡지 않는다.
        // 이 순간 이전에 쌓은 활동은 이 퀘스트의 기여가 아니다.
        if (baselineVal == null) {
            this.baselineVal = cumulative;
            this.lastAppliedMeasuredAt = measuredAt;
            return new ContributionResult.Applied(0);
        }

        // 3. 누적값이 기준보다 작다. 자정 리셋이거나 상류의 데이터 정정이다.
        // 기여분을 되돌리지 않고 무시한다. 다음 스냅샷이 올바른 값을 다시 실어온다.
        int newContributed = cumulative - baselineVal;
        if (newContributed < 0) {
            return new ContributionResult.Ignored(ContributionResult.Reason.NEGATIVE_DELTA);
        }

        // 4. 공유 카운터에 더할 값은 새 기여분과 이전 기여분의 차이다.
        // 이 차이를 먼저 구해두지 않으면 공유 카운터를 갱신할 수가 없다.
        // 정확이 이부분이 dlq를 도입할때 재처리하는 부분을 막는다.
        int counterDelta = newContributed - contributedVal;

        // 5. 멤버 행은 대입이다. 더하기가 아니다.
        this.contributedVal = newContributed;
        this.lastAppliedMeasuredAt = measuredAt;

        return new ContributionResult.Applied(counterDelta);
    }
}
