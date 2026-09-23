package com.workoutdone.rpgym.game.achievement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.workoutdone.rpgym.game.achievement.application.AchievementProgressService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 이 컨슈머는 전용 토픽(health.daily-goal.events, #135)을 읽는다.
 * "정상 이벤트를 그대로 넘긴다" 와 "어떤 입력에도 예외를 안 던진다" 가 핵심이다 --
 * 예외를 던지면 그 파티션이 막히고, 하루 1건짜리 이벤트가 통째로 밀린다.
 */
@ExtendWith(MockitoExtension.class)
class DailyGoalCompletedConsumerTest {

    private static final UUID USER_ID = UUID.fromString("9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33");

    @Mock
    private AchievementProgressService achievementProgressService;

    private DailyGoalCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new DailyGoalCompletedConsumer(objectMapper, achievementProgressService);
    }

    @Test
    @DisplayName("DAILY_GOAL_COMPLETED — activityDate 와 achievedAt(KST→Instant) 이 서비스로 넘어가고 userId 는 envelope 에서 온다")
    void dailyGoalCompleted() {
        consumer.consume("""
                {
                  "eventId": "0f8b1c2d-3e4f-4a5b-8c9d-0e1f2a3b4c5d",
                  "eventType": "DAILY_GOAL_COMPLETED",
                  "occurredAt": "2026-08-28T19:00:05+09:00",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": {
                    "summaryId": "5d2a7c10-9b3e-4f61-a8c4-7e0f2b9d1c33",
                    "activityDate": "2026-08-28",
                    "achievedAt": "2026-08-28T19:00:00+09:00"
                  }
                }
                """);

        verify(achievementProgressService).recordDailyGoal(
                eq(USER_ID), eq(LocalDate.of(2026, 8, 28)), eq(Instant.parse("2026-08-28T10:00:00Z")));
    }

    @Test
    @DisplayName("전용 토픽에 다른 eventType 이 섞여 와도 처리하지 않는다 (health.events 의 2종은 quest 몫)")
    void otherHealthEventsAreIgnored() {
        consumer.consume("""
                {
                  "eventId": "b1f4c8e0-3a52-4d17-9c6e-08f1a7d34b90",
                  "eventType": "HEALTH_ACTIVITY_SYNCED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": { "activityDate": "2026-08-28", "measuredAt": "2026-08-28T10:30:00+09:00",
                            "cumulative": { "steps": 3100, "activeMinutes": 31, "activeCalories": 155 } }
                }
                """);
        consumer.consume("""
                {
                  "eventId": "7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61",
                  "eventType": "QUEST_SUGGESTED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": { "suggestionId": "7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61" }
                }
                """);

        verifyNoInteractions(achievementProgressService);
    }

    @Test
    @DisplayName("achievedAt 누락 — 계약 위반. 건너뛰고 예외를 던지지 않는다 (던지면 파티션이 막힌다)")
    void missingAchievedAtIsSkipped() {
        assertThatCode(() -> consumer.consume("""
                {
                  "eventId": "0f8b1c2d-3e4f-4a5b-8c9d-0e1f2a3b4c5d",
                  "eventType": "DAILY_GOAL_COMPLETED",
                  "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33",
                  "data": { "activityDate": "2026-08-28" }
                }
                """)).doesNotThrowAnyException();

        verifyNoInteractions(achievementProgressService);
    }

    @Test
    @DisplayName("userId 누락 · data 누락 · 깨진 JSON · 모르는 eventType — 전부 예외 없이 지나간다")
    void malformedInputsDoNotThrow() {
        assertThatCode(() -> {
            consumer.consume("""
                    { "eventId": "0f8b1c2d-3e4f-4a5b-8c9d-0e1f2a3b4c5d", "eventType": "DAILY_GOAL_COMPLETED",
                      "data": { "activityDate": "2026-08-28", "achievedAt": "2026-08-28T19:00:00+09:00" } }
                    """);
            consumer.consume("""
                    { "eventId": "0f8b1c2d-3e4f-4a5b-8c9d-0e1f2a3b4c5d", "eventType": "DAILY_GOAL_COMPLETED",
                      "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33" }
                    """);
            consumer.consume("{ this is not json");
            consumer.consume("""
                    { "eventId": "0f8b1c2d-3e4f-4a5b-8c9d-0e1f2a3b4c5d", "eventType": "SOMETHING_NEW",
                      "userId": "9f1c8e2a-4b7d-4c3e-8a11-2f6d9c0b7e33", "data": {} }
                    """);
        }).doesNotThrowAnyException();

        verifyNoInteractions(achievementProgressService);
    }
}
