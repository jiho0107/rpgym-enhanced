package com.workoutdone.rpgym.game.domain.entity.quest.aggregate;

import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuestMember;
import com.workoutdone.rpgym.game.quest.domain.vo.ContributionResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

// 멤버 한 사람의 기여분 계산만 본다
class PartyQuestMemberApplyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);
    private static final Metric METRIC = Metric.STEPS;

    private PartyQuestMember member(Integer baseline) {
        return PartyQuestMember.join(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), baseline);
    }

    private Snapshot snapshot(String utcInstant, int steps) {
        return new Snapshot(DATE, Instant.parse(utcInstant), steps, 0, 0);
    }

    private static int deltaOf(ContributionResult result) {
        return assertInstanceOf(ContributionResult.Applied.class, result).counterDelta();
    }

    @Test
    @DisplayName("기준이 없으면 첫 이벤트로 기준만 정하고 기여는 잡지 않는다")
    void 첫_이벤트는_기준만_정한다() {
        PartyQuestMember member = member(null);

        ContributionResult result = member.apply(snapshot("2026-09-21T01:00:00Z", 4000), METRIC, DATE);

        // 여기서 4000 을 기여로 잡으면 아침에 이미 걸어둔 활동이 통째로 인정된다.
        // 목표가 작으면 퀘스트가 시작하자마자 완료되고 XP 가 공짜로 나간다.
        assertEquals(0, deltaOf(result));
        assertEquals(4000, member.getBaselineVal());
        assertEquals(0, member.getContributedVal());
    }

    @Test
    @DisplayName("기준이 있으면 누적값에서 기준을 뺀 값이 기여분이다")
    void 기여분은_누적값_빼기_기준값이다() {
        PartyQuestMember member = member(3000);

        ContributionResult result = member.apply(snapshot("2026-09-21T02:00:00Z", 5200), METRIC, DATE);

        assertEquals(2200, member.getContributedVal());
        // 이전 기여가 0 이었으므로 공유 카운터에 더할 값도 2200 이다
        assertEquals(2200, deltaOf(result));
    }

    @Test
    @DisplayName("공유 카운터에 더할 값은 새 기여분에서 이전 기여분을 뺀 것이다")
    void 카운터_증분은_기여분의_차이다() {
        PartyQuestMember member = member(3000);
        member.apply(snapshot("2026-09-21T02:00:00Z", 4800), METRIC, DATE);   // 기여 1800

        ContributionResult result = member.apply(snapshot("2026-09-21T02:30:00Z", 5200), METRIC, DATE);

        assertEquals(2200, member.getContributedVal());   // 멤버 행은 대입
        assertEquals(400, deltaOf(result));               // 카운터는 증분
    }

    @Test
    @DisplayName("같은 시각이 다시 오면 무시한다 — 중복이란 정확히 그 경우다")
    void 같은_시각_재수신은_무시한다() {
        PartyQuestMember member = member(3000);
        member.apply(snapshot("2026-09-21T02:00:00Z", 5200), METRIC, DATE);

        ContributionResult result = member.apply(snapshot("2026-09-21T02:00:00Z", 5200), METRIC, DATE);

        assertEquals(ContributionResult.Reason.STALE_SNAPSHOT,
                assertInstanceOf(ContributionResult.Ignored.class, result).reason());
        assertEquals(2200, member.getContributedVal());
    }

    @Test
    @DisplayName("더 이른 시각이 늦게 도착하면 무시한다")
    void 순서_역전은_무시한다() {
        PartyQuestMember member = member(3000);
        member.apply(snapshot("2026-09-21T02:00:00Z", 5200), METRIC, DATE);

        ContributionResult result = member.apply(snapshot("2026-09-21T01:00:00Z", 4000), METRIC, DATE);

        assertEquals(ContributionResult.Reason.STALE_SNAPSHOT,
                assertInstanceOf(ContributionResult.Ignored.class, result).reason());
        assertEquals(2200, member.getContributedVal());
    }

    @Test
    @DisplayName("누적값이 기준보다 작으면 기여분을 되돌리지 않고 무시한다")
    void 음수_기여는_무시한다() {
        PartyQuestMember member = member(3000);
        member.apply(snapshot("2026-09-21T02:00:00Z", 5200), METRIC, DATE);

        // 자정 리셋이거나 상류의 데이터 정정이다
        ContributionResult result = member.apply(snapshot("2026-09-21T15:00:00Z", 100), METRIC, DATE);

        assertEquals(ContributionResult.Reason.NEGATIVE_DELTA,
                assertInstanceOf(ContributionResult.Ignored.class, result).reason());
        assertEquals(2200, member.getContributedVal());
    }

    @Test
    @DisplayName("중간 이벤트가 유실돼도 다음 스냅샷이 알아서 메운다 — 대입이라서 가능한 일이다")
    void 유실은_다음_스냅샷이_메운다() {
        PartyQuestMember member = member(3000);
        member.apply(snapshot("2026-09-21T02:00:00Z", 4000), METRIC, DATE);   // 기여 1000

        // 4500, 5000 짜리 이벤트가 유실되고 5200 이 도착했다고 하자
        ContributionResult result = member.apply(snapshot("2026-09-21T04:00:00Z", 5200), METRIC, DATE);

        // 증분으로 더해 나갔다면 유실된 만큼이 영구히 사라졌을 것이다.
        // 대입이라 최종 기여분이 정확하고, 카운터에도 그 차이가 한 번에 반영된다.
        assertEquals(2200, member.getContributedVal());
        assertEquals(1200, deltaOf(result));
    }

    @Test
    @DisplayName("기준이 없을 때 어제 스냅샷이 오면 기준을 정하지 않는다 — 어제 누적값이 기준이 되면 오늘 기여가 계속 음수다")
    void 기준이_없을_때_어제_스냅샷은_기준을_정하지_않는다() {
        PartyQuestMember member = member(null);

        ContributionResult result = member.apply(
                new Snapshot(DATE.minusDays(1), Instant.parse("2026-09-20T14:30:00Z"), 8000, 0, 0), METRIC, DATE);

        assertEquals(ContributionResult.Reason.OTHER_DAY,
                assertInstanceOf(ContributionResult.Ignored.class, result).reason());
        assertNull(member.getBaselineVal());
        assertNull(member.getLastAppliedMeasuredAt());

        // 오늘 첫 스냅샷이 기준을 정한다
        member.apply(snapshot("2026-09-21T01:00:00Z", 3000), METRIC, DATE);
        assertEquals(3000, member.getBaselineVal());
    }

    @Test
    @DisplayName("기준이 있을 때 어제 스냅샷이 오면 기여로 잡지 않는다 — 잡으면 어제 누적값 빼기 오늘 기준이 공짜 기여가 된다")
    void 기준이_있을_때_어제_스냅샷은_기여로_잡지_않는다() {
        PartyQuestMember member = member(3000);

        ContributionResult result = member.apply(
                new Snapshot(DATE.minusDays(1), Instant.parse("2026-09-20T14:30:00Z"), 8000, 0, 0), METRIC, DATE);

        assertEquals(ContributionResult.Reason.OTHER_DAY,
                assertInstanceOf(ContributionResult.Ignored.class, result).reason());
        assertEquals(0, member.getContributedVal());
        assertNull(member.getLastAppliedMeasuredAt());
    }

    @Test
    @DisplayName("join 직후에는 기준도 워터마크도 비어 있다")
    void 참여_직후_상태() {
        PartyQuestMember member = member(null);

        assertNull(member.getBaselineVal());
        assertNull(member.getLastAppliedMeasuredAt());
        assertEquals(0, member.getContributedVal());
    }
}
