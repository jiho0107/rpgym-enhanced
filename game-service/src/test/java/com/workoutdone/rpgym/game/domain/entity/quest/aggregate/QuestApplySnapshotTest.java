package com.workoutdone.rpgym.game.domain.entity.quest.aggregate;

import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.QuestStatus;

import com.workoutdone.rpgym.game.quest.domain.vo.ApplyResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;
import com.workoutdone.rpgym.game.quest.domain.aggregate.Quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 시나리오
 *
 * 10:30 KST  Quest 생성 "20분 산책하기"  metric=ACTIVE_MINUTES  target=20  baseline=31
 * 23:59:59   만료
 * 달성분 = 스냅샷의 activeMinutes − 31
 */
class QuestApplySnapshotTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    private static final Instant BASELINE_AT = Instant.parse("2026-08-28T01:30:00Z"); // KST 10:30
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-28T14:59:59Z");  // KST 23:59:59
    private static final int BASELINE = 31;
    private static final int TARGET = 20;

    private Quest activeQuest() {
        return Quest.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "20분 산책하기", Metric.ACTIVE_MINUTES,
                TARGET, BASELINE, BASELINE_AT,
                5, EXPIRES_AT
        );
    }

    /** 같은 날짜의 스냅샷. metric이 ACTIVE_MINUTES라 나머지 두 필드는 판정에 쓰이지 않는다. */
    private Snapshot snapshot(String utcInstant, int activeMinutes) {
        return new Snapshot(DATE, Instant.parse(utcInstant), 0, activeMinutes, 0);
    }

    private void assertIgnored(ApplyResult result, ApplyResult.Reason expected) {
        ApplyResult.Ignored ignored = assertInstanceOf(ApplyResult.Ignored.class, result);
        assertEquals(expected, ignored.reason());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정상 경로
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표를 채우면 완료된다")
    void 목표를_채우면_완료된다() {
        Quest quest = activeQuest();
        Snapshot s = snapshot("2026-08-28T09:30:00Z", 53); // KST 18:30, 달성분 22

        ApplyResult result = quest.applySnapshot(s);

        ApplyResult.Completed completed = assertInstanceOf(ApplyResult.Completed.class, result);
        assertEquals(22, completed.achievedDelta());
        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
        assertEquals(Integer.valueOf(53), quest.getLastCumulativeVal());
        assertEquals(s.measuredAt(), quest.getLastAppliedMeasuredAt());
    }

    @Test
    @DisplayName("목표에 못 미치면 진행만 된다 — 이벤트 발행 없음")
    void 목표에_못_미치면_진행만_된다() {
        Quest quest = activeQuest();
        Snapshot s = snapshot("2026-08-28T03:00:00Z", 43); // KST 12:00, 달성분 12

        ApplyResult result = quest.applySnapshot(s);

        ApplyResult.Progressed progressed = assertInstanceOf(ApplyResult.Progressed.class, result);
        assertEquals(12, progressed.achievedDelta());
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals(Integer.valueOf(43), quest.getLastCumulativeVal());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 멱등 · 순서
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 스냅샷이 두 번 와도 한 번만 반영된다 — 중복 도착")
    void 같은_스냅샷이_두_번_와도_한_번만_반영된다() {
        Quest quest = activeQuest();
        Snapshot s = snapshot("2026-08-28T03:00:00Z", 43);

        assertInstanceOf(ApplyResult.Progressed.class, quest.applySnapshot(s));

        // 재전송 / 컨슈머 재시작으로 같은 이벤트가 다시 들어온 상황
        assertIgnored(quest.applySnapshot(s), ApplyResult.Reason.STALE_SNAPSHOT);
        assertEquals(Integer.valueOf(43), quest.getLastCumulativeVal());
        // ★ 여기가 뚫리면 XP가 두 번 지급된다. lastApplied 비교는 '<' 가 아니라 '<=' 여야 한다.
    }

    @Test
    @DisplayName("더 오래된 스냅샷은 무시된다 — 순서 역전")
    void 더_오래된_스냅샷은_무시된다() {
        Quest quest = activeQuest();
        quest.applySnapshot(snapshot("2026-08-28T02:00:00Z", 40)); // KST 11:00 먼저 반영

        ApplyResult result = quest.applySnapshot(snapshot("2026-08-28T01:45:00Z", 36)); // KST 10:45 뒤늦게 도착

        assertIgnored(result, ApplyResult.Reason.STALE_SNAPSHOT);
        assertEquals(Integer.valueOf(40), quest.getLastCumulativeVal()); // 36으로 롤백되지 않는다
    }

    @Test
    @DisplayName("수락 직후 늦게 온 어제 스냅샷은 무시된다 — 어제 누적값 빼기 오늘 baseline 이 공짜 달성이 된다")
    void 수락_직후_어제_스냅샷은_무시된다() {
        Quest quest = activeQuest();

        // 어제 KST 23:30. 누적값 60 - baseline 31 = 29 로 목표 20 을 넘는다
        ApplyResult result = quest.applySnapshot(
                new Snapshot(DATE.minusDays(1), Instant.parse("2026-08-27T14:30:00Z"), 0, 60, 0));

        assertIgnored(result, ApplyResult.Reason.STALE_SNAPSHOT);
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertNull(quest.getLastAppliedMeasuredAt());
        assertNull(quest.getLastCumulativeVal());
    }

    @Test
    @DisplayName("수락 직후 baseline 시각보다 이른 스냅샷은 무시된다 — baseline 시각이 첫 워터마크다")
    void baseline_시각_이전_스냅샷은_무시된다() {
        Quest quest = activeQuest();

        ApplyResult result = quest.applySnapshot(snapshot("2026-08-28T01:00:00Z", 28)); // KST 10:00, baseline 10:30

        assertIgnored(result, ApplyResult.Reason.STALE_SNAPSHOT);
        assertNull(quest.getLastAppliedMeasuredAt());
    }

    @Test
    @DisplayName("baseline 이전 활동은 인정되지 않는다 — 소급 완료 방지")
    void baseline_이전_활동은_인정되지_않는다() {
        Quest quest = activeQuest();

        // 아침에 이미 31분을 걸어둔 상태 그대로. Quest 수락 이후 새로 걸은 건 0분이다.
        ApplyResult result = quest.applySnapshot(snapshot("2026-08-28T02:00:00Z", BASELINE));

        ApplyResult.Progressed progressed = assertInstanceOf(ApplyResult.Progressed.class, result);
        assertEquals(0, progressed.achievedDelta());
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        // 달성분 0도 '반영'이다. 여기서 lastApplied를 갱신해둬야 같은 이벤트 재수신을 걸러낼 수 있다.
        assertEquals(Integer.valueOf(BASELINE), quest.getLastCumulativeVal());
    }

    @Test
    @DisplayName("누적값이 baseline보다 작으면 무시된다 — 자정 리셋 / 데이터 정정")
    void 누적값이_baseline보다_작으면_무시된다() {
        Quest quest = activeQuest();

        ApplyResult result = quest.applySnapshot(snapshot("2026-08-28T02:00:00Z", 5));

        assertIgnored(result, ApplyResult.Reason.NEGATIVE_DELTA);
        assertNull(quest.getLastCumulativeVal()); // 진행도를 음수로 만들지 않는다
    }

    @Test
    @DisplayName("이미 완료된 Quest에 이벤트가 더 와도 다시 완료되지 않는다")
    void 이미_완료된_퀘스트는_다시_완료되지_않는다() {
        Quest quest = activeQuest();
        quest.applySnapshot(snapshot("2026-08-28T09:30:00Z", 53)); // 완료

        ApplyResult result = quest.applySnapshot(snapshot("2026-08-28T10:00:00Z", 70));

        assertIgnored(result, ApplyResult.Reason.NOT_ACTIVE);
        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
        assertEquals(Integer.valueOf(53), quest.getLastCumulativeVal()); // 무시했으므로 갱신도 없다
        // 보상은 한 번뿐. XP 지급은 Completed 결과에서만 일어난다.
    }

    // ─────────────────────────────────────────────────────────────────────
    // 만료 경계 (6번 ★)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("만료 시각 이후의 스냅샷으로는 완료되지 않는다")
    void 만료_이후_스냅샷으로는_완료되지_않는다() {
        Quest quest = activeQuest();

        // 다음날 00:30 KST. 날짜가 넘어갔으므로 activityDate도 다음날이다.
        Snapshot s = new Snapshot(DATE.plusDays(1), Instant.parse("2026-08-28T15:30:00Z"), 0, 60, 0);

        assertIgnored(quest.applySnapshot(s), ApplyResult.Reason.AFTER_EXPIRY);
        assertEquals(QuestStatus.ACTIVE, quest.getStatus()); // 완료되지 않는다 = 보상도 없다
    }

    @Test
    @DisplayName("만료 시각과 같은 시각의 스냅샷은 만료로 본다 — 조회 쿼리(expiredAt > at)와 경계가 같다")
    void 만료_시각_정각의_스냅샷은_만료로_본다() {
        Quest quest = activeQuest();

        Snapshot s = snapshot("2026-08-28T14:59:59Z", 60); // KST 23:59:59 = EXPIRES_AT

        assertIgnored(quest.applySnapshot(s), ApplyResult.Reason.AFTER_EXPIRY);
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
    }

    @Test
    @DisplayName("★ 만료 직전 스냅샷은 처리가 자정을 넘겨도 완료된다")
    void 만료_직전_스냅샷은_처리가_늦어도_완료된다() {
        Quest quest = activeQuest();

        // 유저는 23:59:00 에 목표를 채웠다. 그런데 컨슈머가 23:59:30 에 죽고 00:00:30 에 살아나
        // 밀린 이벤트를 그때 처리한다 — 장애 실험 "만료 경계 재처리" 그 상황.
        Snapshot s = snapshot("2026-08-28T14:59:00Z", 60); // KST 23:59:00, 달성분 29

        ApplyResult result = quest.applySnapshot(s);

        ApplyResult.Completed completed = assertInstanceOf(ApplyResult.Completed.class, result);
        assertEquals(29, completed.achievedDelta());
        assertEquals(QuestStatus.COMPLETED, quest.getStatus());

        // 이 테스트에는 "언제 처리했는가"를 넣을 자리가 없다. 그게 핵심이다.
        // now() 로 만료를 판정했다면 이 케이스는 실패하고, 유저는 만료 전에 채우고도 보상을 못 받는다.
    }
}
