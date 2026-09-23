package com.workoutdone.rpgym.health.summary.application;

import com.workoutdone.rpgym.health.activity.application.SyncedActivity;
import com.workoutdone.rpgym.health.summary.adapter.out.UserHealthContextResponse;
import com.workoutdone.rpgym.health.summary.adapter.out.UserServiceClient;
import com.workoutdone.rpgym.health.summary.domain.DailyGoalProgress;
import com.workoutdone.rpgym.health.summary.domain.DailyGoalProgressRepository;
import com.workoutdone.rpgym.health.summary.domain.DailyHealthSummary;
import com.workoutdone.rpgym.health.summary.domain.DailyHealthSummaryRepository;
import com.workoutdone.rpgym.health.summary.domain.MetricType;
import com.workoutdone.rpgym.health.outbox.application.EventOutboxPort;
import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;
import com.workoutdone.rpgym.health.summary.application.event.DailyGoalCompletedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthSummarySyncService {

    private final DailyHealthSummaryRepository summaryRepository;
    private final DailyGoalProgressRepository progressRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserServiceClient userServiceClient;
    private final EventOutboxPort eventOutboxPort;
    private final Clock clock;

    private static final BigDecimal DEFAULT_STEP_GOAL = BigDecimal.valueOf(5000);
    private static final BigDecimal DEFAULT_ACTIVE_MINUTES_GOAL = BigDecimal.valueOf(60);
    private static final BigDecimal DEFAULT_ACTIVE_CALORIES_GOAL = BigDecimal.valueOf(300);

    private static final List<MetricType> METRIC_PRIORITY = List.of(
            MetricType.STEPS, MetricType.ACTIVE_MINUTES, MetricType.ACTIVE_CALORIES
    );
    private static final Duration QUEST_SUGGESTION_INTERVAL = Duration.ofMinutes(30);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Transactional
    public void sync(SyncedActivity syncedActivity) {
        UUID userId = syncedActivity.userId();
        LocalDate activityDate = syncedActivity.activityDate();
        Instant measuredAt = syncedActivity.measuredAt();
        int steps = syncedActivity.steps();
        int activeMinutes = syncedActivity.activeMinutes();
        int activeCalories = syncedActivity.activeCalories();

        Instant now = clock.instant();

        DailyHealthSummary summary = summaryRepository.findByUserIdAndActivityDate(userId, activityDate).orElse(null);

        List<DailyGoalProgress> progresses;
        boolean isNew = summary == null;
        if (isNew) {
            summary = DailyHealthSummary.createFor(userId, activityDate, now);
        }

        boolean applied = summary.applySync(steps, activeMinutes, activeCalories, measuredAt, now);
        if (!applied) {
            return;
        }

        summary = summaryRepository.save(summary);

        if (isNew) {
            progresses = createInitialProgresses(summary, userId, activityDate);
        } else {
            progresses = progressRepository.findBySummaryId(summary.getSummaryId());
        }

        for (DailyGoalProgress progress : progresses) {
            BigDecimal achievedValue = resolveAchievedValue(progress.getMetricType(), steps, activeMinutes, activeCalories);
            progress.updateAchieved(achievedValue);
        }
        progressRepository.saveAll(progresses);

        boolean allAchieved = !progresses.isEmpty()
                && progresses.stream().allMatch(DailyGoalProgress::isAchieved);
        if (allAchieved) {
            boolean newlyAchieved = summary.markAllGoalsAchieved(now);
            summaryRepository.save(summary);
            if (newlyAchieved) {
                publishDailyGoalCompletedEvent(summary, syncedActivity.activityId());
            }
        } else {
            publishDeficientGoalEventIfNeeded(summary, syncedActivity.activityId(), progresses, now);
        }
    }

    private void publishDeficientGoalEventIfNeeded(DailyHealthSummary summary, UUID activityId,
                                                   List<DailyGoalProgress> progresses, Instant now) {
        /*
         * 자정 이후 "어제" 범위를 재집계해서 어제 날짜(activityDate)로 보내는
         * 정정 동기화(#124, 외부 기기 수집 경로의 자정 직전 정정 누락 보완 규약)는
         * Quest 제안 대상이 아니다. Game Service는 오늘 발급된 퀘스트만 기대하므로,
         * * 어제 날짜 기준으로 제안하면 SUGGESTION_DATE_MISMATCH로 거부되거나 이미 만료된
         * 퀘스트가 생성될 수 있다.
         */
        LocalDate today = now.atZone(KST).toLocalDate();
        if (!summary.getActivityDate().isEqual(today)) {
            return;
        }

        if (!summary.isQuestSuggestionDue(now, QUEST_SUGGESTION_INTERVAL)) {
            return;
        }
        summary.recordQuestSuggested(now);
        summaryRepository.save(summary);

        Optional<DailyGoalProgress> mostDeficient = progresses.stream()
                .filter(p -> !p.isAchieved())
                .max(Comparator
                        .comparing(this::achievementDeficitRatio)
                        .thenComparing(p -> METRIC_PRIORITY.indexOf(p.getMetricType()), Comparator.reverseOrder())
                );

        mostDeficient.ifPresent(progress -> eventPublisher.publishEvent(new DeficientGoalDetectedEvent(
                summary.getUserId(),
                summary.getSummaryId(),
                activityId,
                summary.getActivityDate(),
                summary.getLastSyncedAt(),
                progress.getMetricType().name(),
                progress.getShortageValue().intValue()
        )));
    }

    private void publishDailyGoalCompletedEvent(DailyHealthSummary summary, UUID activityId) {
        UUID eventId = UUID.randomUUID();
        String dedupKey = "DAILY_GOAL_COMPLETED:%s:%s".formatted(summary.getUserId(), summary.getActivityDate());

        DailyGoalCompletedPayload payload = new DailyGoalCompletedPayload(
                summary.getSummaryId(),
                summary.getActivityDate(),
                summary.getAchievedAt()
        );

        eventOutboxPort.append(
                eventId,
                HealthEventType.DAILY_GOAL_COMPLETED,
                summary.getUserId(),
                activityId,
                dedupKey,
                payload
        );
    }

    private BigDecimal achievementDeficitRatio(DailyGoalProgress progress) {
        BigDecimal targetValue = progress.getTargetValue();
        if (targetValue.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return progress.getShortageValue().divide(targetValue, 4, RoundingMode.HALF_UP);
    }

    private List<DailyGoalProgress> createInitialProgresses(DailyHealthSummary summary, UUID userId, LocalDate activityDate) {
        UserHealthContextResponse.DailyGoal dailyGoal = fetchDailyGoal(userId);

        BigDecimal stepGoal = dailyGoal != null && dailyGoal.stepGoal() != null
                ? BigDecimal.valueOf(dailyGoal.stepGoal()) : DEFAULT_STEP_GOAL;
        BigDecimal activeMinutesGoal = dailyGoal != null && dailyGoal.activeMinutesGoal() != null
                ? BigDecimal.valueOf(dailyGoal.activeMinutesGoal()) : DEFAULT_ACTIVE_MINUTES_GOAL;
        BigDecimal activeCaloriesGoal = dailyGoal != null && dailyGoal.activeCaloriesGoal() != null
                ? BigDecimal.valueOf(dailyGoal.activeCaloriesGoal()) : DEFAULT_ACTIVE_CALORIES_GOAL;

        List<DailyGoalProgress> progresses = List.of(
                DailyGoalProgress.createFor(summary.getSummaryId(), userId, activityDate, MetricType.STEPS, stepGoal),
                DailyGoalProgress.createFor(summary.getSummaryId(), userId, activityDate, MetricType.ACTIVE_MINUTES, activeMinutesGoal),
                DailyGoalProgress.createFor(summary.getSummaryId(), userId, activityDate, MetricType.ACTIVE_CALORIES, activeCaloriesGoal)
        );
        return progressRepository.saveAll(progresses);
    }

    private UserHealthContextResponse.DailyGoal fetchDailyGoal(UUID userId) {
        try {
            UserHealthContextResponse response = userServiceClient.getHealthContext(userId);
            return response.dailyGoal();
        } catch (Exception e) {
            log.warn("User Service health-context 조회 실패, 기본값 사용. userId={}", userId, e);
            return null;
        }
    }

    private BigDecimal resolveAchievedValue(MetricType metricType, int steps, int activeMinutes, int activeCalories) {
        return switch (metricType) {
            case STEPS -> BigDecimal.valueOf(steps);
            case ACTIVE_MINUTES -> BigDecimal.valueOf(activeMinutes);
            case ACTIVE_CALORIES -> BigDecimal.valueOf(activeCalories);
        };
    }
}