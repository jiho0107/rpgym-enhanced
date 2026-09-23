package com.workoutdone.rpgym.health.outbox.application;

import com.workoutdone.rpgym.health.outbox.config.OutboxPublishProperties;
import com.workoutdone.rpgym.health.outbox.domain.EventOutbox;
import com.workoutdone.rpgym.health.outbox.domain.EventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;

import java.util.List;

/**
 * Outbox → Kafka 발행기의 한 라운드.
 *
 * PENDING 행을 SKIP LOCKED로 집어 순서대로 발행하고 상태를 전이한다.
 * 상태 변경은 더티 체킹으로 반영되므로 별도 save 호출이 없다.
 *
 * 발행 토픽은 이벤트 타입별로 정해진다. (OutboxPublishProperties#topicFor)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final EventOutboxRepository eventOutboxRepository;
    private final EventPublisherPort eventPublisherPort;
    private final OutboxPublishProperties properties;
    private final OutboxMetrics outboxMetrics;

    /** @return 이번 라운드에 발행 성공한 건수 */
    @Transactional
    public int relayOnce() {
        List<EventOutbox> pendingEvents =
                eventOutboxRepository.findPendingForUpdate(properties.pollSize());

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;

        for (EventOutbox outbox : pendingEvents) {
            if (!relay(outbox)) {
                /*
                 * 한 건이라도 실패하면 라운드를 중단한다.
                 *
                 * 1) 같은 userId의 뒤 이벤트를 먼저 보내면 파티션 내 순서가 뒤집힌다.
                 * 2) 브로커 장애라면 남은 건도 전부 타임아웃이라 배치 전체가 지연된다.
                 *
                 * 남은 PENDING은 그대로 남아 다음 폴링에서 다시 집힌다.
                 */
                log.warn("발행 실패로 이번 라운드를 중단한다. 처리 성공={} 남은 대상={}",
                        publishedCount, pendingEvents.size() - publishedCount);
                break;
            }
            publishedCount++;
        }

        return publishedCount;
    }

    private boolean relay(EventOutbox outbox) {
        long startNanos = System.nanoTime();

        try {
            /*
             * payload 컬럼의 문자열을 그대로 보낸다.
             * 발행 시점에 JSON을 재구성하면 eventId가 재전송마다 바뀌어
             * Game Service의 중복 처리 방지가 무력화된다. (이전 PR 리뷰)
             */
            eventPublisherPort.publish(
                    properties.topicFor(outbox.getEventType()),
                    outbox.getPartitionKey(),
                    outbox.getPayload(),
                    outbox.getEventType()
            );

            outbox.markPublished();

            outboxMetrics.recordPublishSuccess(
                    outbox.getEventType(),
                    Duration.ofNanos(System.nanoTime() - startNanos),
                    publishLagOf(outbox)
            );

            log.debug("이벤트 발행 완료. eventType={} eventId={}",
                    outbox.getEventType(), outbox.getEventId());
            return true;

        } catch (Exception e) {
            outboxMetrics.recordPublishFailure(outbox.getEventType());
            handleFailure(outbox, e);
            return false;
        }
    }

    /** 적재 시각부터 발행 시각까지의 지연. 감사 시각이 없으면 0으로 본다 */
    private Duration publishLagOf(EventOutbox outbox) {
        if (outbox.getCreatedAt() == null || outbox.getPublishedAt() == null) {
            return Duration.ZERO;
        }
        return Duration.between(outbox.getCreatedAt(), outbox.getPublishedAt());
    }

    private void handleFailure(EventOutbox outbox, Exception e) {
        int attempts = outbox.getRetryCount() + 1;

        if (attempts < properties.maxRetry()) {
            outbox.markRetryable();
            log.warn("이벤트 발행 실패. 다음 폴링에서 재시도한다. eventId={} 시도={}/{} 원인={}",
                    outbox.getEventId(), attempts, properties.maxRetry(), e.toString());
            return;
        }

        moveToDlq(outbox, attempts, e);
    }

    private void moveToDlq(EventOutbox outbox, int attempts, Exception cause) {
        // 원래 발행 토픽에 대응하는 DLQ로 보낸다 (health.events.dlq / health.daily-goal.events.dlq)
        String dlqTopic = properties.dlqTopicFor(outbox.getEventType());

        try {
            eventPublisherPort.publishToDlq(
                    dlqTopic,
                    outbox.getPartitionKey(),
                    outbox.getPayload(),
                    outbox.getEventType(),
                    attempts,
                    cause.toString()
            );

            outbox.markFailed();
            outboxMetrics.recordDlq(outbox.getEventType());
            log.error("최대 재시도를 초과해 DLQ로 이동한다. eventId={} dlqTopic={} 시도={}",
                    outbox.getEventId(), dlqTopic, attempts, cause);

        } catch (Exception dlqError) {
            /*
             * DLQ 발행까지 실패했다면 브로커 자체가 죽은 상황이다.
             * FAILED로 종료해버리면 되살릴 방법이 수동 조작밖에 남지 않으므로 PENDING으로 되돌린다.
             */
            outbox.markRetryable();
            log.error("DLQ 발행까지 실패해 PENDING으로 되돌린다. eventId={}",
                    outbox.getEventId(), dlqError);
        }
    }
}