package com.workoutdone.rpgym.game.domain.entity.quest.aggregate;

import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.QuestStatus;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;
import com.workoutdone.rpgym.game.quest.domain.aggregate.Quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 조회 계층에 노출되는 파생값. 저장된 값이 아니라 읽을 때 계산되는 값들이다.
 *
 * 이 두 메서드가 엔티티 안에 있는 이유 — 컨트롤러나 DTO 매퍼에서 계산하면
 * 같은 공식이 여러 곳에 복제되고, 목록 API와 단건 API가 서로 다른 값을 내놓는 사고로 이어진다.
 */
class QuestProgressTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    private static final Instant BASELINE_AT = Instant.parse("2026-08-28T01:30:00Z"); // KST 10:30
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-28T14:59:59Z");  // KST 23:59:59
    private static final int BASELINE = 31;

    private Quest activeQuest() {
        return Quest.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "20분 산책하기", Metric.ACTIVE_MINUTES,
                20, BASELINE, BASELINE_AT,
                5, EXPIRES_AT
        );
    }

    private Snapshot snapshot(String utcInstant, int activeMinutes) {
        return new Snapshot(DATE, Instant.parse(utcInstant), 0, activeMinutes, 0);
    }

    // progress()

    @Test
    @DisplayName("첫 이벤트가 오기 전에는 진행도가 0이다")
    void 첫_이벤트_전에는_진행도가_0이다() {
        assertEquals(0, activeQuest().progress());
    }

    @Test
    @DisplayName("진행도는 마지막 누적값에서 baseline을 뺀 값이다")
    void 진행도는_마지막_누적값에서_baseline을_뺀_값이다() {
        Quest quest = activeQuest();
        quest.applySnapshot(snapshot("2026-08-28T03:00:00Z", 43));

        assertEquals(12, quest.progress()); // 43 - 31
    }

    @Test
    @DisplayName("무시된 스냅샷은 진행도를 바꾸지 않는다")
    void 무시된_스냅샷은_진행도를_바꾸지_않는다() {
        Quest quest = activeQuest();
        quest.applySnapshot(snapshot("2026-08-28T03:00:00Z", 43));
        quest.applySnapshot(snapshot("2026-08-28T02:00:00Z", 36)); // 순서 역전 → 무시

        assertEquals(12, quest.progress()); // 5로 떨어지지 않는다
    }

    // displayStatus()

    @Test
    @DisplayName("만료 시각이 지난 ACTIVE는 EXPIRED로 표시된다 — 스케줄러가 아직 안 돌았어도")
    void 만료_시각이_지난_ACTIVE는_EXPIRED로_표시된다() {
        Quest quest = activeQuest(); // DB status 는 여전히 ACTIVE

        assertEquals(QuestStatus.EXPIRED, quest.displayStatus(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    @DisplayName("만료 시각 정각에는 EXPIRED다 — applySnapshot · 조회 쿼리와 경계가 같다")
    void 만료_정각에는_EXPIRED다() {
        Quest quest = activeQuest();

        assertEquals(QuestStatus.EXPIRED, quest.displayStatus(EXPIRES_AT));
    }

    @Test
    @DisplayName("만료 시각 직전에는 아직 ACTIVE다")
    void 만료_직전에는_아직_ACTIVE다() {
        Quest quest = activeQuest();

        assertEquals(QuestStatus.ACTIVE, quest.displayStatus(EXPIRES_AT.minusMillis(1)));
    }

    @Test
    @DisplayName("완료된 Quest는 만료 시각이 지나도 COMPLETED로 표시된다")
    void 완료된_퀘스트는_만료_후에도_COMPLETED다() {
        Quest quest = activeQuest();
        quest.applySnapshot(snapshot("2026-08-28T09:30:00Z", 53)); // 완료

        assertEquals(QuestStatus.COMPLETED, quest.displayStatus(EXPIRES_AT.plusSeconds(3600)));
    }
}
