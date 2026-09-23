package com.workoutdone.rpgym.health.outbox.config;

import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox 발행기 설정.
 *
 * 토픽은 소비 측 컨슈머와 맞춰야 하므로 코드가 아니라 설정으로 둔다.
 *
 * 토픽은 이벤트 타입별로 나뉜다.
 *  - HEALTH_ACTIVITY_SYNCED, QUEST_SUGGESTED → topic
 *    30분마다 발행된다. 같은 sync에서 함께 나가는데, 토픽이 분리되면 각각 소비되어
 *    Quest baseline이 옛날 누적값으로 고정될 수 있으므로 반드시 한 토픽에 둔다. (팀 합의)
 *  - DAILY_GOAL_COMPLETED → dailyGoalTopic
 *    사용자당 하루 1번 발행된다. 위 순서 제약과 무관하고 발행 주기가 달라 전용 토픽으로 분리한다.
 *    따라서 HEALTH_ACTIVITY_SYNCED와의 소비 순서는 보장되지 않는다.
 */
@ConfigurationProperties(prefix = "rpgym.outbox")
public record OutboxPublishProperties(

        // 한 라운드에 조회할 PENDING 최대 건수
        @DefaultValue("100") int pollSize,

        // 이 횟수만큼 실패하면 DLQ로 보내고 FAILED로 종료한다
        @DefaultValue("5") int maxRetry,

        // Kafka 발행 응답 대기 시간
        @DefaultValue("5s") Duration sendTimeout,

        // HEALTH_ACTIVITY_SYNCED, QUEST_SUGGESTED 발행 토픽
        @DefaultValue("health.events") String topic,

        // DAILY_GOAL_COMPLETED 전용 발행 토픽
        @DefaultValue("health.daily-goal.events") String dailyGoalTopic,

        // DLQ 토픽 접미사. 각 발행 토픽 뒤에 붙는다
        @DefaultValue(".dlq") String dlqSuffix
) {

    /**
     * 이벤트 타입에 맞는 발행 토픽.
     *
     * default 분기를 두지 않는다. 이벤트 타입이 추가되면
     * 컴파일 에러로 라우팅 누락이 바로 드러나게 하기 위해서다.
     */
    public String topicFor(HealthEventType eventType) {
        return switch (eventType) {
            case HEALTH_ACTIVITY_SYNCED, QUEST_SUGGESTED -> topic;
            case DAILY_GOAL_COMPLETED -> dailyGoalTopic;
        };
    }

    /** 이벤트 타입에 맞는 DLQ 토픽 (health.events.dlq / health.daily-goal.events.dlq) */
    public String dlqTopicFor(HealthEventType eventType) {
        return topicFor(eventType) + dlqSuffix;
    }
}