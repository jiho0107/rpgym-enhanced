package com.workoutdone.rpgym.health.summary.application;

import com.workoutdone.rpgym.health.activity.application.SyncedActivity;
import com.workoutdone.rpgym.health.outbox.application.EventOutboxPort;
import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;
import com.workoutdone.rpgym.health.summary.adapter.out.UserServiceClient;
import com.workoutdone.rpgym.health.summary.domain.DailyGoalProgress;
import com.workoutdone.rpgym.health.summary.domain.DailyGoalProgressRepository;
import com.workoutdone.rpgym.health.summary.domain.DailyHealthSummary;
import com.workoutdone.rpgym.health.summary.domain.DailyHealthSummaryRepository;
import com.workoutdone.rpgym.health.summary.domain.MetricType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HealthSummarySyncServiceTest {

    @Mock
    DailyHealthSummaryRepository summaryRepository;
    @Mock
    DailyGoalProgressRepository progressRepository;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    UserServiceClient userServiceClient;
    @Mock
    EventOutboxPort eventOutboxPort;

    private HealthSummarySyncService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();
    private final LocalDate activityDate = LocalDate.of(2026, 8, 30);

    @BeforeEach
    void setUp() {
        given(summaryRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(progressRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        service = new HealthSummarySyncService(
                summaryRepository, progressRepository, eventPublisher, userServiceClient, eventOutboxPort, Clock.systemUTC()
        );
    }

    private void useClock(Instant fixedNow) {
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        service = new HealthSummarySyncService(
                summaryRepository, progressRepository, eventPublisher, userServiceClient, eventOutboxPort, clock
        );
    }

    private DailyGoalProgress achievedProgress(UUID summaryId, MetricType metricType, BigDecimal target) {
        DailyGoalProgress progress = DailyGoalProgress.createFor(summaryId, userId, activityDate, metricType, target);
        progress.updateAchieved(target);
        return progress;
    }

    @Test
    void 최초로_목표를_전부_달성하면_DailyGoalCompleted_이벤트가_발행된다() {
        Instant earlier = Instant.parse("2026-08-30T01:00:00Z");
        useClock(earlier.plusSeconds(600));
        DailyHealthSummary summary = DailyHealthSummary.createFor(userId, activityDate, earlier);
        summary.applySync(4000, 50, 250, earlier, earlier);

        UUID summaryId = summary.getSummaryId();
        List<DailyGoalProgress> progresses = List.of(
                achievedProgress(summaryId, MetricType.STEPS, BigDecimal.valueOf(5000)),
                achievedProgress(summaryId, MetricType.ACTIVE_MINUTES, BigDecimal.valueOf(60)),
                achievedProgress(summaryId, MetricType.ACTIVE_CALORIES, BigDecimal.valueOf(300))
        );

        given(summaryRepository.findByUserIdAndActivityDate(userId, activityDate))
                .willReturn(Optional.of(summary));
        given(progressRepository.findBySummaryId(summaryId)).willReturn(progresses);

        Instant measuredAt = earlier.plusSeconds(600);
        SyncedActivity syncedActivity = new SyncedActivity(
                activityId, userId, activityDate, measuredAt, 5000, 60, 300
        );

        service.sync(syncedActivity);

        String expectedDedupKey = "DAILY_GOAL_COMPLETED:%s:%s".formatted(userId, activityDate);

        verify(eventOutboxPort).append(
                any(), eq(HealthEventType.DAILY_GOAL_COMPLETED), eq(userId), eq(activityId),
                eq(expectedDedupKey), any()
        );
    }

    @Test
    void 이미_달성한_날짜에_또_동기화해도_이벤트가_재발행되지_않는다() {
        Instant earlier = Instant.parse("2026-08-30T01:00:00Z");
        useClock(earlier.plusSeconds(600));
        DailyHealthSummary summary = DailyHealthSummary.createFor(userId, activityDate, earlier);
        summary.applySync(5000, 60, 300, earlier, earlier);
        summary.markAllGoalsAchieved(earlier);

        UUID summaryId = summary.getSummaryId();
        List<DailyGoalProgress> progresses = List.of(
                achievedProgress(summaryId, MetricType.STEPS, BigDecimal.valueOf(5000)),
                achievedProgress(summaryId, MetricType.ACTIVE_MINUTES, BigDecimal.valueOf(60)),
                achievedProgress(summaryId, MetricType.ACTIVE_CALORIES, BigDecimal.valueOf(300))
        );

        given(summaryRepository.findByUserIdAndActivityDate(userId, activityDate))
                .willReturn(Optional.of(summary));
        given(progressRepository.findBySummaryId(summaryId)).willReturn(progresses);

        Instant measuredAt = earlier.plusSeconds(600);
        SyncedActivity syncedActivity = new SyncedActivity(
                activityId, userId, activityDate, measuredAt, 5000, 60, 300
        );

        service.sync(syncedActivity);

        verify(eventOutboxPort, never()).append(
                any(), eq(HealthEventType.DAILY_GOAL_COMPLETED), any(), any(), any(), any()
        );
    }

    @Test
    void 이미_실패처리된_날짜는_뒤늦게_달성해도_완료처리되지_않는다() {
        Instant earlier = Instant.parse("2026-08-30T01:00:00Z");
        useClock(earlier.plusSeconds(600));
        DailyHealthSummary summary = DailyHealthSummary.createFor(userId, activityDate, earlier);
        summary.applySync(3000, 40, 200, earlier, earlier);
        summary.markAsFailed(earlier.plusSeconds(300));

        UUID summaryId = summary.getSummaryId();
        List<DailyGoalProgress> progresses = List.of(
                achievedProgress(summaryId, MetricType.STEPS, BigDecimal.valueOf(5000)),
                achievedProgress(summaryId, MetricType.ACTIVE_MINUTES, BigDecimal.valueOf(60)),
                achievedProgress(summaryId, MetricType.ACTIVE_CALORIES, BigDecimal.valueOf(300))
        );

        given(summaryRepository.findByUserIdAndActivityDate(userId, activityDate))
                .willReturn(Optional.of(summary));
        given(progressRepository.findBySummaryId(summaryId)).willReturn(progresses);

        Instant lateMeasuredAt = earlier.plusSeconds(600);
        SyncedActivity syncedActivity = new SyncedActivity(
                activityId, userId, activityDate, lateMeasuredAt, 5000, 60, 300
        );

        service.sync(syncedActivity);

        assertThat(summary.getFailedAt()).isNotNull();
        assertThat(summary.getAchievedAt()).isNull();
        verify(eventOutboxPort, never()).append(
                any(), eq(HealthEventType.DAILY_GOAL_COMPLETED), any(), any(), any(), any()
        );
    }

    @Test
    void 목표_미달_상태에서_오늘_날짜면_Quest_제안이_정상_발행된다() {
        Instant now = Instant.parse("2026-08-30T02:00:00Z");
        useClock(now);
        DailyHealthSummary summary = DailyHealthSummary.createFor(userId, activityDate, now);

        UUID summaryId = summary.getSummaryId();
        List<DailyGoalProgress> progresses = List.of(
                DailyGoalProgress.createFor(summaryId, userId, activityDate, MetricType.STEPS, BigDecimal.valueOf(5000)),
                DailyGoalProgress.createFor(summaryId, userId, activityDate, MetricType.ACTIVE_MINUTES, BigDecimal.valueOf(60)),
                DailyGoalProgress.createFor(summaryId, userId, activityDate, MetricType.ACTIVE_CALORIES, BigDecimal.valueOf(300))
        );

        given(summaryRepository.findByUserIdAndActivityDate(userId, activityDate))
                .willReturn(Optional.of(summary));
        given(progressRepository.findBySummaryId(summaryId)).willReturn(progresses);

        SyncedActivity syncedActivity = new SyncedActivity(
                activityId, userId, activityDate, now, 1000, 10, 50
        );

        service.sync(syncedActivity);

        verify(eventPublisher).publishEvent(any(DeficientGoalDetectedEvent.class));
    }

    @Test
    void 정정_동기화로_어제_날짜가_들어오면_Quest_제안을_건너뛴다() {
        LocalDate yesterday = activityDate;
        Instant lateNightToday = Instant.parse("2026-08-30T15:30:00Z");
        Instant measuredAtWithinYesterday = Instant.parse("2026-08-30T14:59:00Z");
        useClock(lateNightToday);
        DailyHealthSummary summary = DailyHealthSummary.createFor(userId, yesterday, lateNightToday);

        UUID summaryId = summary.getSummaryId();
        List<DailyGoalProgress> progresses = List.of(
                DailyGoalProgress.createFor(summaryId, userId, yesterday, MetricType.STEPS, BigDecimal.valueOf(5000)),
                DailyGoalProgress.createFor(summaryId, userId, yesterday, MetricType.ACTIVE_MINUTES, BigDecimal.valueOf(60)),
                DailyGoalProgress.createFor(summaryId, userId, yesterday, MetricType.ACTIVE_CALORIES, BigDecimal.valueOf(300))
        );

        given(summaryRepository.findByUserIdAndActivityDate(userId, yesterday))
                .willReturn(Optional.of(summary));
        given(progressRepository.findBySummaryId(summaryId)).willReturn(progresses);

        SyncedActivity syncedActivity = new SyncedActivity(
                activityId, userId, yesterday, measuredAtWithinYesterday, 1000, 10, 50
        );

        service.sync(syncedActivity);

        verify(eventPublisher, never()).publishEvent(any(DeficientGoalDetectedEvent.class));
    }
}
