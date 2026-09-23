package com.workoutdone.rpgym.health.outbox.application;

import com.workoutdone.rpgym.health.outbox.config.OutboxPublishProperties;
import com.workoutdone.rpgym.health.outbox.domain.EventOutbox;
import com.workoutdone.rpgym.health.outbox.domain.EventOutboxRepository;
import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;
import com.workoutdone.rpgym.health.outbox.domain.OutboxStatus;
import com.workoutdone.rpgym.health.outbox.exception.EventPublishException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "health.events";
    private static final String DLQ_TOPIC = "health.events.dlq";
    private static final String DAILY_GOAL_TOPIC = "health.daily-goal.events";
    private static final String DAILY_GOAL_DLQ_TOPIC = "health.daily-goal.events.dlq";
    private static final int MAX_RETRY = 3;

    @Mock
    private EventOutboxRepository eventOutboxRepository;

    @Mock
    private EventPublisherPort eventPublisherPort;

    private OutboxRelay outboxRelay;

    /**
     * 지표는 목이 아니라 실제 객체를 쓴다.
     * 발행 경로에서 지표를 기록하는 코드까지 실제로 실행되므로,
     * 태그 조합이나 Duration 계산이 깨지면 이 테스트에서 드러난다.
     */
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        // 순서: pollSize, maxRetry, sendTimeout, topic, dailyGoalTopic, dlqSuffix
        OutboxPublishProperties properties = new OutboxPublishProperties(
                100, MAX_RETRY, Duration.ofSeconds(5), TOPIC, DAILY_GOAL_TOPIC, ".dlq");

        meterRegistry = new SimpleMeterRegistry();

        outboxRelay = new OutboxRelay(
                eventOutboxRepository,
                eventPublisherPort,
                properties,
                new OutboxMetrics(meterRegistry));
    }

    /** event_type + result 조합의 발행 카운터 값 */
    private double publishCount(String result) {
        return meterRegistry.get("rpgym.outbox.publish")
                .tags("event_type", HealthEventType.HEALTH_ACTIVITY_SYNCED.name(), "result", result)
                .counter()
                .count();
    }

    @Test
    @DisplayName("발행에 성공하면 PUBLISHED로 전이하고 payload 원문을 그대로 보낸다")
    void marksPublishedOnSuccess() {
        // given
        EventOutbox outbox = pendingOutbox();
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));

        // when
        int publishedCount = outboxRelay.relayOnce();

        // then
        assertThat(publishedCount).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isNotNull();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        then(eventPublisherPort).should().publish(
                eq(TOPIC),
                eq(outbox.getPartitionKey()),
                payloadCaptor.capture(),
                eq(HealthEventType.HEALTH_ACTIVITY_SYNCED));

        // 발행 시점에 JSON을 재구성하면 eventId가 바뀐다. 저장된 문자열 그대로여야 한다.
        assertThat(payloadCaptor.getValue()).isSameAs(outbox.getPayload());

        assertThat(publishCount("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DAILY_GOAL_COMPLETED는 전용 토픽(health.daily-goal.events)으로 발행한다")
    void routesDailyGoalCompletedToDedicatedTopic() {
        // given
        EventOutbox outbox = pendingOutbox(HealthEventType.DAILY_GOAL_COMPLETED);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));

        // when
        outboxRelay.relayOnce();

        // then
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        then(eventPublisherPort).should().publish(
                eq(DAILY_GOAL_TOPIC),
                eq(outbox.getPartitionKey()),
                eq(outbox.getPayload()),
                eq(HealthEventType.DAILY_GOAL_COMPLETED));
        then(eventPublisherPort).should(never()).publish(
                eq(TOPIC), anyString(), anyString(), any(HealthEventType.class));
    }

    @Test
    @DisplayName("QUEST_SUGGESTED는 HEALTH_ACTIVITY_SYNCED와 같은 토픽(health.events)에 남는다")
    void keepsQuestSuggestedOnSharedTopic() {
        // given
        EventOutbox outbox = pendingOutbox(HealthEventType.QUEST_SUGGESTED);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));

        // when
        outboxRelay.relayOnce();

        // then — 순서 보장이 필요한 두 이벤트는 반드시 같은 토픽이어야 한다
        then(eventPublisherPort).should().publish(
                eq(TOPIC),
                eq(outbox.getPartitionKey()),
                eq(outbox.getPayload()),
                eq(HealthEventType.QUEST_SUGGESTED));
    }

    @Test
    @DisplayName("한 라운드에 섞여 있어도 각 이벤트는 자기 토픽으로 적재 순서대로 발행된다")
    void routesMixedEventsInOrder() {
        // given — 같은 sync에서 Synced → DailyGoalCompleted 순으로 적재된 상황
        EventOutbox synced = pendingOutbox(HealthEventType.HEALTH_ACTIVITY_SYNCED);
        EventOutbox dailyGoal = pendingOutbox(HealthEventType.DAILY_GOAL_COMPLETED);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(synced, dailyGoal));

        // when
        int publishedCount = outboxRelay.relayOnce();

        // then
        assertThat(publishedCount).isEqualTo(2);

        InOrder inOrder = inOrder(eventPublisherPort);
        inOrder.verify(eventPublisherPort).publish(
                eq(TOPIC), anyString(), anyString(), eq(HealthEventType.HEALTH_ACTIVITY_SYNCED));
        inOrder.verify(eventPublisherPort).publish(
                eq(DAILY_GOAL_TOPIC), anyString(), anyString(), eq(HealthEventType.DAILY_GOAL_COMPLETED));
    }

    @Test
    @DisplayName("발행에 실패하고 재시도 여유가 있으면 PENDING을 유지하고 retryCount만 늘린다")
    void keepsPendingWhenRetryBudgetRemains() {
        // given
        EventOutbox outbox = pendingOutbox();
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));
        givenPublishFails();

        // when
        int publishedCount = outboxRelay.relayOnce();

        // then
        assertThat(publishedCount).isZero();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        then(eventPublisherPort).should(never()).publishToDlq(
                anyString(), anyString(), anyString(),
                any(HealthEventType.class), anyInt(), anyString());

        assertThat(publishCount("failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("최대 재시도에 도달하면 DLQ로 보내고 FAILED로 종료한다")
    void movesToDlqWhenRetryExhausted() {
        // given — 이미 MAX_RETRY - 1회 실패한 상태
        EventOutbox outbox = pendingOutbox();
        failBefore(outbox, MAX_RETRY - 1);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));
        givenPublishFails();

        // when
        outboxRelay.relayOnce();

        // then
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        then(eventPublisherPort).should().publishToDlq(
                eq(DLQ_TOPIC),
                eq(outbox.getPartitionKey()),
                eq(outbox.getPayload()),
                eq(HealthEventType.HEALTH_ACTIVITY_SYNCED),
                eq(MAX_RETRY),
                anyString());
    }

    @Test
    @DisplayName("DAILY_GOAL_COMPLETED가 최대 재시도에 도달하면 전용 DLQ로 보낸다")
    void movesDailyGoalCompletedToDedicatedDlq() {
        // given
        EventOutbox outbox = pendingOutbox(HealthEventType.DAILY_GOAL_COMPLETED);
        failBefore(outbox, MAX_RETRY - 1);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));
        givenPublishFails();

        // when
        outboxRelay.relayOnce();

        // then
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        then(eventPublisherPort).should().publishToDlq(
                eq(DAILY_GOAL_DLQ_TOPIC),
                eq(outbox.getPartitionKey()),
                eq(outbox.getPayload()),
                eq(HealthEventType.DAILY_GOAL_COMPLETED),
                eq(MAX_RETRY),
                anyString());
        then(eventPublisherPort).should(never()).publishToDlq(
                eq(DLQ_TOPIC), anyString(), anyString(),
                any(HealthEventType.class), anyInt(), anyString());
    }

    @Test
    @DisplayName("DLQ 발행까지 실패하면 PENDING으로 되돌려 다음 폴링에서 재시도한다")
    void revertsToPendingWhenDlqFails() {
        // given
        EventOutbox outbox = pendingOutbox();
        failBefore(outbox, MAX_RETRY - 1);
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(outbox));
        givenPublishFails();
        willThrow(new EventPublishException(DLQ_TOPIC, new RuntimeException("broker down")))
                .given(eventPublisherPort).publishToDlq(
                        anyString(), anyString(), anyString(),
                        any(HealthEventType.class), anyInt(), anyString());

        // when
        outboxRelay.relayOnce();

        // then
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("한 건이 실패하면 같은 라운드의 뒤 이벤트는 발행하지 않는다")
    void stopsRoundOnFirstFailure() {
        // given
        EventOutbox first = pendingOutbox();
        EventOutbox second = pendingOutbox();
        given(eventOutboxRepository.findPendingForUpdate(anyInt())).willReturn(List.of(first, second));
        givenPublishFails();

        // when
        outboxRelay.relayOnce();

        // then — 첫 건에서만 발행을 시도하고 멈춘다
        then(eventPublisherPort).should(times(1)).publish(
                anyString(), anyString(), anyString(), any(HealthEventType.class));
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getRetryCount()).isZero();
    }

    private void givenPublishFails() {
        willThrow(new EventPublishException(TOPIC, new RuntimeException("broker down")))
                .given(eventPublisherPort).publish(
                        anyString(), anyString(), anyString(), any(HealthEventType.class));
    }

    /** 이전에 count번 실패한 상태로 만든다 (PENDING 유지, retryCount 증가) */
    private void failBefore(EventOutbox outbox, int count) {
        for (int i = 0; i < count; i++) {
            outbox.markRetryable();
        }
    }

    private EventOutbox pendingOutbox() {
        return pendingOutbox(HealthEventType.HEALTH_ACTIVITY_SYNCED);
    }

    private EventOutbox pendingOutbox(HealthEventType eventType) {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        return EventOutbox.pending(
                UUID.randomUUID(),
                eventId,
                eventType,
                UUID.randomUUID(),
                eventType.name() + ":" + userId + ":2026-08-28T10:30:00+09:00",
                userId.toString(),
                """
                {"eventId":"%s","eventType":"%s"}
                """.formatted(eventId, eventType.name())
        );
    }
}