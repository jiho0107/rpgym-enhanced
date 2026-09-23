package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.AchievementScope;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * "이벤트 한 건 → 업적 N개 갱신 → 딴 것만 돌려준다" 흐름.
 * 업적은 이력만 쌓는다. XP 는 이 서비스가 모른다.
 */
class AchievementProgressServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final Instant AT = Instant.parse("2026-09-01T10:00:00Z");

    private AchievementRepository achievementRepository;
    private UserAchievementRepository userAchievementRepository;
    private AchievementProgressService sut;

    private Achievement first;    // COUNT 1
    private Achievement streak3;  // STREAK 3

    @BeforeEach
    void setUp() {
        achievementRepository = mock(AchievementRepository.class);
        userAchievementRepository = mock(UserAchievementRepository.class);
        sut = new AchievementProgressService(achievementRepository, userAchievementRepository);

        first = Achievement.create(UUID.randomUUID(), "DAILY_GOAL_FIRST", "첫 걸음", "일일 목표를 처음으로 달성",
                AchievementScope.PERSONAL, ConditionType.DAILY_GOAL_COUNT, 1, 0);
        streak3 = Achievement.create(UUID.randomUUID(), "DAILY_GOAL_STREAK_3", "3일 연속", "일일 목표를 3일 연속 달성",
                AchievementScope.PERSONAL, ConditionType.DAILY_GOAL_STREAK, 3, 0);

        given(achievementRepository.findActiveByConditionTypes(ConditionType.DAILY_GOAL)).willReturn(List.of(first, streak3));
        given(userAchievementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("첫 이벤트 — 진행 행이 없으면 업적마다 만들고, FIRST 만 ACHIEVED 로 돌아온다")
    void firstEventCreatesRowsAndUnlocksFirst() {
        given(userAchievementRepository.findAllByOwner(OwnerType.USER, USER)).willReturn(List.of());

        List<Achievement> unlocked = sut.recordDailyGoal(USER, D1, AT);

        assertThat(unlocked).containsExactly(first);

        ArgumentCaptor<UserAchievement> saved = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(ua -> {
            assertThat(ua.getOwnerType()).isEqualTo(OwnerType.USER);
            assertThat(ua.getOwnerId()).isEqualTo(USER);
            assertThat(ua.getLastCountedDate()).isEqualTo(D1);
        });

        UserAchievement firstRow = saved.getAllValues().stream()
                .filter(ua -> ua.getAchievementId().equals(first.getId())).findFirst().orElseThrow();
        assertThat(firstRow.isAchieved()).isTrue();
        assertThat(firstRow.getAchievedAt()).isEqualTo(AT);

        UserAchievement streakRow = saved.getAllValues().stream()
                .filter(ua -> ua.getAchievementId().equals(streak3.getId())).findFirst().orElseThrow();
        assertThat(streakRow.isAchieved()).isFalse();
        assertThat(streakRow.getCurrentValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 날짜 재전송 — 저장이 한 번도 일어나지 않는다")
    void duplicateDateIsNoOp() {
        UserAchievement firstRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, USER, first.getId());
        firstRow.count(D1, ConditionType.DAILY_GOAL_COUNT, 1, AT);
        UserAchievement streakRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, USER, streak3.getId());
        streakRow.count(D1, ConditionType.DAILY_GOAL_STREAK, 3, AT);
        given(userAchievementRepository.findAllByOwner(OwnerType.USER, USER)).willReturn(List.of(firstRow, streakRow));

        List<Achievement> unlocked = sut.recordDailyGoal(USER, D1, AT);

        assertThat(unlocked).isEmpty();
        verify(userAchievementRepository, never()).save(any());
    }

    @Test
    @DisplayName("둘째 날 — FIRST 는 이미 ACHIEVED 라 건너뛰고 STREAK 만 2로 올라간다")
    void secondDayProgressesStreakOnly() {
        UserAchievement firstRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, USER, first.getId());
        firstRow.count(D1, ConditionType.DAILY_GOAL_COUNT, 1, AT);
        UserAchievement streakRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, USER, streak3.getId());
        streakRow.count(D1, ConditionType.DAILY_GOAL_STREAK, 3, AT);
        given(userAchievementRepository.findAllByOwner(OwnerType.USER, USER)).willReturn(List.of(firstRow, streakRow));

        List<Achievement> unlocked = sut.recordDailyGoal(USER, D2, AT.plusSeconds(86_400));

        assertThat(unlocked).isEmpty();
        verify(userAchievementRepository, times(1)).save(streakRow);
        verify(userAchievementRepository, never()).save(firstRow);
        assertThat(streakRow.getCurrentValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("ACTIVE 업적이 하나도 없으면 아무것도 하지 않는다")
    void noActiveAchievements() {
        given(achievementRepository.findActiveByConditionTypes(ConditionType.DAILY_GOAL)).willReturn(List.of());

        List<Achievement> unlocked = sut.recordDailyGoal(USER, D1, AT);

        assertThat(unlocked).isEmpty();
        verifyNoInteractions(userAchievementRepository);
    }
}
