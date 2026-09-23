package com.workoutdone.rpgym.health.activity.application;

import com.workoutdone.rpgym.health.activity.domain.ActivitySnapshot;
import com.workoutdone.rpgym.health.activity.domain.ActivitySource;
import com.workoutdone.rpgym.health.activity.domain.HealthActivity;
import com.workoutdone.rpgym.health.activity.domain.HealthActivityRepository;
import com.workoutdone.rpgym.health.outbox.application.EventOutboxPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthActivityServiceTest {

    @Mock
    HealthActivityRepository healthActivityRepository;
    @Mock
    EventOutboxPort eventOutboxPort;
    @Mock
    DailyProgressUpdatePort dailyProgressUpdatePort;
    @InjectMocks
    HealthActivityService healthActivityService;

    private final UUID userId = UUID.randomUUID();
    /** KST 2026-08-28 10:30 */
    private final Instant measuredAt = Instant.parse("2026-08-28T01:30:00Z");

    private SyncHealthActivityCommand command(int steps) {
        return new SyncHealthActivityCommand(
                userId, measuredAt, steps, 31, 155, ActivitySource.SYNTHETIC);
    }

    private HealthActivity activity(int steps) {
        return HealthActivity.sync(UUID.randomUUID(), userId, measuredAt,
                ActivitySnapshot.of(steps, 31, 155), ActivitySource.SYNTHETIC);
    }

    @Test
    @DisplayName("신규 스냅샷이면 저장하고 Outbox에 이벤트를 기록한다")
    void sync_newSnapshot_savesAndAppendsOutboxEvent() {
        given(healthActivityRepository.findByUserIdAndMeasuredAt(userId, measuredAt))
                .willReturn(Optional.empty());
        given(healthActivityRepository.findLatestSnapshot(eq(userId), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(healthActivityRepository.save(any())).willAnswer(i -> i.getArgument(0));

        HealthActivitySyncResult result = healthActivityService.sync(command(3100));

        assertThat(result.created()).isTrue();
        assertThat(result.view().steps()).isEqualTo(3100);
        verify(healthActivityRepository).save(any(HealthActivity.class));
        verify(eventOutboxPort).append(any(), any(), eq(userId), any(), anyString(), any());
        verify(dailyProgressUpdatePort).applySync(any(SyncedActivity.class));
    }

    @Test
    @DisplayName("measuredAt에서 activityDate를 KST 기준으로 파생시킨다")
    void sync_derivesActivityDateInKst() {
        given(healthActivityRepository.findByUserIdAndMeasuredAt(userId, measuredAt))
                .willReturn(Optional.empty());
        given(healthActivityRepository.findLatestSnapshot(eq(userId), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(healthActivityRepository.save(any())).willAnswer(i -> i.getArgument(0));

        HealthActivitySyncResult result = healthActivityService.sync(command(3100));

        assertThat(result.view().activityDate()).isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("값까지 동일한 재전송이면 갱신도 이벤트 기록도 하지 않는다")
    void sync_identicalResend_doesNothing() {
        given(healthActivityRepository.findByUserIdAndMeasuredAt(userId, measuredAt))
                .willReturn(Optional.of(activity(3100)));

        HealthActivitySyncResult result = healthActivityService.sync(command(3100));

        assertThat(result.created()).isFalse();
        verify(healthActivityRepository, never()).save(any());
        verifyNoInteractions(eventOutboxPort);
        verifyNoInteractions(dailyProgressUpdatePort);
    }

    @Test
    @DisplayName("같은 시점에 다른 값이 오면 스냅샷만 갱신하고 이벤트는 다시 기록하지 않는다")
    void sync_resyncWithDifferentValues_updatesSnapshotOnly() {
        HealthActivity existing = activity(3100);
        given(healthActivityRepository.findByUserIdAndMeasuredAt(userId, measuredAt))
                .willReturn(Optional.of(existing));

        HealthActivitySyncResult result = healthActivityService.sync(command(3500));

        assertThat(result.created()).isFalse();
        assertThat(result.view().steps()).isEqualTo(3500);
        assertThat(existing.getActivityDate()).isEqualTo(LocalDate.of(2026, 8, 28));
        verifyNoInteractions(eventOutboxPort);
        // 이벤트는 다시 안 내보내지만 요약은 다시 계산해야 한다 (윤석 님과 합의한 재동기화 경로)
        verify(dailyProgressUpdatePort).applySync(any(SyncedActivity.class));
    }

    @Test
    @DisplayName("오늘 이력이 없으면 0값 View를 반환한다")
    void getToday_noHistory_returnsZeroView() {
        given(healthActivityRepository.findLatestSnapshot(eq(userId), any(LocalDate.class)))
                .willReturn(Optional.empty());

        HealthActivityView view = healthActivityService.getToday(userId);

        assertThat(view.activityId()).isNull();
        assertThat(view.steps()).isZero();
        assertThat(view.activeMinutes()).isZero();
        assertThat(view.activeCalories()).isZero();
    }
}