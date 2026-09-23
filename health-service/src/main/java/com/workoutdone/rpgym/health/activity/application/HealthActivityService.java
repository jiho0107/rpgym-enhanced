package com.workoutdone.rpgym.health.activity.application;

import com.workoutdone.rpgym.health.activity.application.event.HealthActivitySyncedData;
import com.workoutdone.rpgym.health.activity.domain.ActivitySnapshot;
import com.workoutdone.rpgym.health.activity.domain.HealthActivity;
import com.workoutdone.rpgym.health.activity.domain.HealthActivityRepository;
import com.workoutdone.rpgym.health.outbox.application.EventOutboxPort;
import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthActivityService implements HealthActivitySyncUseCase, HealthActivityQueryUseCase {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HealthActivityRepository healthActivityRepository;
    private final EventOutboxPort eventOutboxPort;
    private final DailyProgressUpdatePort dailyProgressUpdatePort;

    /**
     * 건강 활동 동기화.
     *
     * (userId, measuredAt)이 이미 있으면 스냅샷만 갱신한다 (명세: 존재하면 UPDATE, 없으면 INSERT).
     * 값까지 같은 완전 중복 재전송이면 UPDATE도 하지 않는다.
     *
     * 이벤트는 신규 저장일 때만 기록한다. dedupKey가 (userId, measuredAt)이라
     * 재동기화 시 어차피 차단되며, todo : 누적값 설계상 정정분은 다음 스냅샷이 메운다. -> 추가 기능 구현시 고려 사항
     */
    @Override
    @Transactional
    public HealthActivitySyncResult sync(SyncHealthActivityCommand command) {

        ActivitySnapshot snapshot = ActivitySnapshot.of(
                command.steps(), command.activeMinutes(), command.activeCalories());

        Optional<HealthActivity> existing = healthActivityRepository
                .findByUserIdAndMeasuredAt(command.userId(), command.measuredAt());

        if (existing.isPresent()) {
            HealthActivity activity = existing.get();

            if (activity.hasSameSnapshot(snapshot)) {
                log.debug("동일 스냅샷 재전송이라 갱신하지 않는다. userId={} measuredAt={}",
                        command.userId(), command.measuredAt());
            } else {
                log.info("재동기화로 스냅샷을 갱신한다. userId={} measuredAt={}",
                        command.userId(), command.measuredAt());
                activity.resync(snapshot, command.source());

                // 누적값이 바뀌었으므로 달성률도 다시 계산해야 한다.
                // 이걸 빼면 외부 소스의 중복 제거나 기록 삭제 후 요약이 옛날 값으로 남는다.
                dailyProgressUpdatePort.applySync(SyncedActivity.from(activity));
            }
            return new HealthActivitySyncResult(HealthActivityView.of(activity), false);
        }

        HealthActivity activity = HealthActivity.sync(
                UUID.randomUUID(),
                command.userId(),
                command.measuredAt(),
                snapshot,
                command.source()
        );

        warnIfCumulativeDecreased(activity);

        HealthActivity saved = healthActivityRepository.save(activity);

        eventOutboxPort.append(
                UUID.randomUUID(),
                HealthEventType.HEALTH_ACTIVITY_SYNCED,
                saved.getUserId(),
                saved.getActivityId(),
                dedupKey(saved),
                HealthActivitySyncedData.from(saved)
        );

        // HealthActivitySynced를 Outbox에 기록한 뒤에 호출한다. (적재 순서: Synced → DailyGoalCompleted)
        // 단, DailyGoalCompleted는 전용 토픽(health.daily-goal.events)으로 나가므로
        // 컨슈머 입장에서 두 이벤트의 소비 순서는 보장되지 않는다.
        dailyProgressUpdatePort.applySync(SyncedActivity.from(saved));

        return new HealthActivitySyncResult(HealthActivityView.of(saved), true);
    }

    /** 오늘(KST)의 최신 누적 스냅샷. 이력이 없으면 0값으로 응답한다. */
    @Override
    @Transactional(readOnly = true)
    public HealthActivityView getToday(UUID userId) {
        LocalDate today = LocalDate.now(KST);
        return healthActivityRepository.findLatestSnapshot(userId, today)
                .map(HealthActivityView::of)
                .orElseGet(() -> HealthActivityView.empty(userId, today));
    }

    /**
     * 같은 날짜의 더 이른 시점보다 누적값이 작아진 경우를 기록만 남긴다.
     *
     * 누적값은 시간이 지날수록 커져야 하므로 정상이라면 발생하지 않는다.
     * 다만 외부 데이터 소스의 중복 제거나 사용자의 운동 기록 삭제로 값이 줄어들 수 있어
     * 요청을 거절하지는 않는다. Game Service가 baseline 차이로 진행도를 계산하므로,
     * 값 역전이 일어났다는 사실은 추적할 수 있어야 한다.
     */
    private void warnIfCumulativeDecreased(HealthActivity incoming) {
        healthActivityRepository
                .findLatestSnapshot(incoming.getUserId(), incoming.getActivityDate())
                .filter(latest -> latest.getMeasuredAt().isBefore(incoming.getMeasuredAt()))
                .filter(latest -> !incoming.getSnapshot().isNotLessThan(latest.getSnapshot()))
                .ifPresent(latest -> log.warn(
                        "누적값이 이전 스냅샷보다 작다. userId={} activityDate={} 이전={} 이번={}",
                        incoming.getUserId(), incoming.getActivityDate(),
                        latest.getMeasuredAt(), incoming.getMeasuredAt()));
    }

    private String dedupKey(HealthActivity activity) {
        return HealthEventType.HEALTH_ACTIVITY_SYNCED.name()
                + ":" + activity.getUserId()
                + ":" + activity.getMeasuredAt();
    }
}