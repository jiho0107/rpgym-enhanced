package com.workoutdone.rpgym.game.quest.application;

import com.workoutdone.rpgym.game.outbox.application.OutboxRecorder;
import com.workoutdone.rpgym.game.outbox.domain.AggregateType;
import com.workoutdone.rpgym.game.outbox.domain.OutboxEventType;
import com.workoutdone.rpgym.game.quest.application.payload.QuestCompletedData;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.aggregate.Quest;
import com.workoutdone.rpgym.game.quest.domain.aggregate.UserLatestSnapshot;
import com.workoutdone.rpgym.game.quest.domain.repo.QuestRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.UserLatestSnapshotRepository;
import com.workoutdone.rpgym.game.quest.domain.vo.ApplyResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;
import com.workoutdone.rpgym.game.xp.application.XpGrantService;
import com.workoutdone.rpgym.game.xp.domain.SourceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestProgressServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    private static final Instant BASELINE_AT = Instant.parse("2026-08-28T01:30:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-28T14:59:59Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-28T09:30:00Z");
    private static final int BASELINE = 31;
    private static final int TARGET = 20;
    private static final int REWARD_XP = 5;

    private QuestRepository questRepository;
    private UserLatestSnapshotRepository snapshotRepository;
    private XpGrantService xpGrantService;
    private OutboxRecorder outboxRecorder;
    private QuestProgressService service;
    private Quest quest;

    @BeforeEach
    void setUp() {
        questRepository = mock(QuestRepository.class);
        snapshotRepository = mock(UserLatestSnapshotRepository.class);
        xpGrantService = mock(XpGrantService.class);
        outboxRecorder = mock(OutboxRecorder.class);
        service = new QuestProgressService(
                questRepository, snapshotRepository, xpGrantService, outboxRecorder);

        quest = Quest.create(
                UUID.randomUUID(), USER_ID, UUID.randomUUID(),
                "20분 산책하기", Metric.ACTIVE_MINUTES,
                TARGET, BASELINE, BASELINE_AT, REWARD_XP, EXPIRES_AT);

        when(questRepository.findActiveByUserId(eq(USER_ID), any())).thenReturn(Optional.of(quest));
        when(snapshotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }

    private Snapshot snapshot(String utcInstant, int activeMinutes) {
        return new Snapshot(DATE, Instant.parse(utcInstant), 0, activeMinutes, 0);
    }

    @Test
    @DisplayName("목표를 채우면 XP 지급과 QuestCompleted 적재가 함께 일어난다")
    void 완료되면_XP와_Outbox가_함께_일어난다() {
        Optional<ApplyResult> result = service.apply(USER_ID, snapshot("2026-08-28T09:30:00Z", 53));

        assertInstanceOf(ApplyResult.Completed.class, result.orElseThrow());
        verify(xpGrantService, times(1)).grant(
                eq(USER_ID), eq(SourceType.QUEST), eq(quest.getQuestId()), eq(REWARD_XP), eq(COMPLETED_AT));
        verify(outboxRecorder, times(1)).append(
                eq(AggregateType.QUEST), eq(quest.getQuestId()), eq(OutboxEventType.QUEST_COMPLETED),
                eq(USER_ID), eq(COMPLETED_AT), any());
    }

    @Test
    @DisplayName("완료 이벤트에 퀘스트 제목이 실린다 — 알림 쪽이 이것만으로 카드를 그릴 수 있어야 한다")
    void 완료_이벤트에_제목이_실린다() {
        ArgumentCaptor<QuestCompletedData> payload = ArgumentCaptor.forClass(QuestCompletedData.class);

        service.apply(USER_ID, snapshot("2026-08-28T09:30:00Z", 53));

        verify(outboxRecorder).append(any(), any(), any(), any(), any(), payload.capture());
        QuestCompletedData data = payload.getValue();
        // 제목이 없으면 카드에 클라이언트가 읽을 내용이 하나도 남지 않는다. 퀘스트 식별자는 UUID다.
        assertEquals("20분 산책하기", data.title());
        assertEquals(quest.getQuestId(), data.questId());
        assertEquals("ACTIVE_MINUTES", data.metric());
        assertEquals(TARGET, data.targetValue());
        assertEquals(BASELINE, data.baselineValue());
        assertEquals(53 - BASELINE, data.achievedDelta());
        assertEquals(REWARD_XP, data.rewardXp());
        assertEquals(COMPLETED_AT, data.completedByMeasuredAt());
    }

    @Test
    @DisplayName("진행만 되면 XP도 발행도 없다")
    void 진행만_되면_지급도_발행도_없다() {
        Optional<ApplyResult> result = service.apply(USER_ID, snapshot("2026-08-28T05:00:00Z", 40));

        assertInstanceOf(ApplyResult.Progressed.class, result.orElseThrow());
        verify(xpGrantService, never()).grant(any(), any(), any(), anyInt(), any());
        verify(outboxRecorder, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 measuredAt이 다시 와도 진행도가 움직이지 않는다 — at-least-once 재수신")
    void 같은_measuredAt_재수신은_무시된다() {
        Snapshot arriving = snapshot("2026-08-28T05:00:00Z", 40);

        service.apply(USER_ID, arriving);
        Optional<ApplyResult> second = service.apply(USER_ID, arriving);

        ApplyResult.Ignored ignored = assertInstanceOf(ApplyResult.Ignored.class, second.orElseThrow());
        assertEquals(ApplyResult.Reason.STALE_SNAPSHOT, ignored.reason());
        assertEquals(9, quest.progress());
        verify(xpGrantService, never()).grant(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("완료를 만든 스냅샷이 다시 와도 XP는 한 번만 나간다")
    void 완료_스냅샷_재수신에도_XP는_한_번이다() {
        Snapshot completing = snapshot("2026-08-28T09:30:00Z", 53);

        service.apply(USER_ID, completing);
        Optional<ApplyResult> second = service.apply(USER_ID, completing);

        ApplyResult.Ignored ignored = assertInstanceOf(ApplyResult.Ignored.class, second.orElseThrow());
        assertEquals(ApplyResult.Reason.NOT_ACTIVE, ignored.reason());
        verify(xpGrantService, times(1)).grant(any(), any(), any(), anyInt(), any());
        verify(outboxRecorder, times(1)).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("수락 직후 늦게 온 어제 스냅샷으로는 XP도 이벤트도 없다")
    void 늦게_온_어제_스냅샷으로는_보상이_없다() {
        // 어제 KST 23:30. 반영하면 60 - 31 = 29 로 목표 20 을 넘는다
        Snapshot yesterday = new Snapshot(DATE.minusDays(1), Instant.parse("2026-08-27T14:30:00Z"), 0, 60, 0);

        Optional<ApplyResult> result = service.apply(USER_ID, yesterday);

        assertInstanceOf(ApplyResult.Ignored.class, result.orElseThrow());
        verify(xpGrantService, never()).grant(any(), any(), any(), anyInt(), any());
        verify(outboxRecorder, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("활성 Quest가 없어도 스냅샷은 저장된다")
    void 활성_Quest가_없어도_스냅샷은_저장된다() {
        when(questRepository.findActiveByUserId(eq(USER_ID), any())).thenReturn(Optional.empty());

        Optional<ApplyResult> result = service.apply(USER_ID, snapshot("2026-08-28T05:00:00Z", 40));

        assertTrue(result.isEmpty());
        verify(snapshotRepository).save(any(UserLatestSnapshot.class));
        verify(questRepository, never()).save(any());
    }

    @Test
    @DisplayName("더 오래된 스냅샷은 저장하지 않는다 — 과거가 최신을 덮으면 baseline이 소급된다")
    void 더_오래된_스냅샷은_저장하지_않는다() {
        UserLatestSnapshot stored = UserLatestSnapshot.create(
                USER_ID, new Snapshot(DATE, Instant.parse("2026-08-28T06:00:00Z"), 8000, 60, 400));
        when(snapshotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(stored));

        service.apply(USER_ID, snapshot("2026-08-28T05:00:00Z", 40));

        verify(snapshotRepository, never()).save(any());
    }
}
