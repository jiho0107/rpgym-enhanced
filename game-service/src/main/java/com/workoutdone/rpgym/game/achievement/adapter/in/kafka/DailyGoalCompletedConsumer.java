package com.workoutdone.rpgym.game.achievement.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workoutdone.rpgym.game.achievement.adapter.in.kafka.dto.DailyGoalCompletedData;
import com.workoutdone.rpgym.game.achievement.adapter.in.kafka.dto.HealthEventEnvelope;
import com.workoutdone.rpgym.game.achievement.application.AchievementProgressService;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * health.daily-goal.events 에서 DAILY_GOAL_COMPLETED 를 받아 업적에 넘긴다.
 *
 * 토픽이 quest 의 HealthEventConsumer(health.events) 와 다르다 -- Health 가 #135 에서 이 이벤트만
 * 전용 토픽으로 분리했다. 발행 주기가 달라서다. Synced · QuestSuggested 는 30분마다, 이건 하루 1번이다.
 * 섞어 두면 하루 1건을 받자고 30분 주기 이벤트를 전부 읽어 걸러야 하고, 적체 · 유실이 눈에 띄지 않는다.
 *
 * 순서 제약은 없다. 단일 토픽을 쓰던 이유(Synced -> QuestSuggested 순서로 Quest baseline 을 잡는 것)는
 * 저 둘 사이의 문제고, 이 이벤트는 거기 끼지 않는다. 업적은 activityDate 로 판정하므로 도착 순서와 무관하다.
 *
 * 토픽 · 그룹 ID 는 프로퍼티로 빼되 기본값을 여기 둔다. application.yml 을 안 건드려도 동작한다 --
 * 그 파일은 quest 담당과 같이 쓰는 파일이라 건드릴수록 충돌이 난다.
 *
 * eventType 검사는 남겨 둔다. 전용 토픽이라 다른 타입이 올 일은 없지만, 한 토픽에 다른 이벤트가
 * 추가되는 날 조용히 잘못 처리하는 것보다 걸러내는 편이 안전하다.
 *
 * 예외 정책은 quest 컨슈머와 같다. 계약 위반(필드 누락 · 깨진 JSON)은 로그만 남기고 ack --
 * 몇 번을 다시 해도 같은 결과라 재시도가 의미 없고, 던지면 그 파티션이 영원히 막힌다.
 * DB 다운 · 낙관적 락 충돌은 서비스에서 올라오는 대로 던져 offset 을 잡지 않는다 (KafkaConsumerConfig 가 무한 재시도).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyGoalCompletedConsumer {

    static final String DAILY_GOAL_COMPLETED = "DAILY_GOAL_COMPLETED";

    private final ObjectMapper objectMapper;
    private final AchievementProgressService achievementProgressService;

    @KafkaListener(
            topics = "${rpgym.kafka.daily-goal-events-topic:health.daily-goal.events}",
            groupId = "${rpgym.kafka.achievement-consumer-group:game-service-achievement}"
    )
    public void consume(String message) {
        HealthEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, HealthEventEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("health event 역직렬화 실패. 건너뛴다. message={}", message, e);
            return;
        }

        // 전용 토픽이라 정상이면 항상 통과한다. 다른 타입이 섞여 오면 조용히 넘긴다.
        if (!DAILY_GOAL_COMPLETED.equals(envelope.eventType())) {
            log.warn("전용 토픽에 다른 eventType 이 왔다. 건너뛴다. eventType={}", envelope.eventType());
            return;
        }
        if (envelope.userId() == null) {
            log.error("DAILY_GOAL_COMPLETED envelope 에 userId 가 없다. 건너뛴다. eventId={}", envelope.eventId());
            return;
        }

        MDC.put("eventId", String.valueOf(envelope.eventId()));
        MDC.put("userId", String.valueOf(envelope.userId()));
        try {
            record(envelope);
        } finally {
            MDC.remove("eventId");
            MDC.remove("userId");
        }
    }

    private void record(HealthEventEnvelope envelope) {
        if (envelope.data() == null || envelope.data().isNull()) {
            log.error("DAILY_GOAL_COMPLETED data 가 비어 있다. 건너뛴다.");
            return;
        }

        DailyGoalCompletedData data;
        try {
            data = objectMapper.treeToValue(envelope.data(), DailyGoalCompletedData.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("DAILY_GOAL_COMPLETED data 변환 실패. 건너뛴다. data={}", envelope.data(), e);
            return;
        }

        // activityDate 는 판정 키, achievedAt 은 user_achievements.achieved_at 에 그대로 남는 값. 둘 다 없으면 계약 위반.
        if (data.activityDate() == null || data.achievedAt() == null) {
            log.error("DAILY_GOAL_COMPLETED 필수 필드 누락. 건너뛴다. data={}", envelope.data());
            return;
        }

        List<Achievement> unlocked = achievementProgressService.recordDailyGoal(
                envelope.userId(), data.activityDate(), data.achievedAt().toInstant());
        log.debug("DAILY_GOAL_COMPLETED 처리 완료. activityDate={} unlocked={}",
                data.activityDate(), unlocked.stream().map(Achievement::getCode).toList());
    }
}
