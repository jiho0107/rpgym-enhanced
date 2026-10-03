package com.workoutdone.rpgym.game.quest.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workoutdone.rpgym.game.quest.adapter.in.kafka.dto.HealthActivitySyncedData;
import com.workoutdone.rpgym.game.quest.adapter.in.kafka.dto.HealthEventEnvelope;
import com.workoutdone.rpgym.game.quest.adapter.in.kafka.dto.QuestSuggestedData;
import com.workoutdone.rpgym.game.quest.application.PartyQuestProgressService;
import com.workoutdone.rpgym.game.quest.application.QuestProgressService;
import com.workoutdone.rpgym.game.quest.application.QuestSuggestionCommand;
import com.workoutdone.rpgym.game.quest.application.QuestSuggestionService;
import com.workoutdone.rpgym.game.quest.application.SuggestionOutcome;
import com.workoutdone.rpgym.game.quest.domain.vo.ApplyResult;
import com.workoutdone.rpgym.game.quest.domain.vo.ContributionResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * health.events 토픽 하나에서 3종을 받아 분기한다.
 *
 * 이 클래스가 하는 일은 셋뿐이다 -- 역직렬화 / 도메인 타입으로 변환 / 서비스 호출.
 * 트랜잭션을 열지 않고(경계는 서비스에 있다), 판정을 하지 않는다.
 *
 * 특히 HEALTH_ACTIVITY_SYNCED를 "활성 Quest가 있나"로 미리 거르지 않는다.
 * 그 판단은 QuestProgressService 안에 있고, 여기서 거르면 baseline 조달 설계가 무너진다.
 *
 * 예외를 던지느냐 마느냐가 이 클래스의 핵심 결정이다.
 *   던진다  -> offset 미커밋 -> 재시도. DB 다운 · 낙관적 락 충돌처럼 "다시 하면 되는 것"
 *   안 던진다 -> 정상 ack.    계약 위반처럼 "몇 번을 해도 같은 결과인 것"
 * 계약 위반에 예외를 던지면 그 파티션이 영원히 막힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthEventConsumer {

    private static final String HEALTH_ACTIVITY_SYNCED = "HEALTH_ACTIVITY_SYNCED";
    private static final String QUEST_SUGGESTED = "QUEST_SUGGESTED";
    private static final String DAILY_GOAL_COMPLETED = "DAILY_GOAL_COMPLETED";
    private static final int MAX_TITLE_LENGTH = 100;

    private final ObjectMapper objectMapper;
    private final QuestProgressService questProgressService;
    private final PartyQuestProgressService partyQuestProgressService;
    private final QuestSuggestionService questSuggestionService;

    // inbound(Driving) HEALTH_ACTIVITY_SYNCED, QUEST_SUGGESTED 트랜잭션을 시작하기 위한 어댑터
    // 순서대로 T1, T2로 명명
    // T1은 컨슈머가 apply, T2는 컨슈머가 store
    @KafkaListener(topics = "${rpgym.kafka.health-events-topic}")
    public void consume(String message) {
        HealthEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, HealthEventEnvelope.class);
        } catch (JsonProcessingException e) {
            // 재시도해도 같은 문자열이 같은 곳에서 깨진다.
            // 재시도해도 실패할 파싱 에러(DLQ 대상)이므로 메시지를
            // Consume하지 않고 안전하게 건너뛰려는 의도의 코드
            log.error("health event 역직렬화 실패. 건너뛴다. message={}", message, e);
            return;
        }

        // eventType이 null이면 아래 switch가 NPE를 던지고, 그 NPE는 무한 재시도가 된다.
        // 컨슈머가 ACK를 보내지 못하고, 메시지 소비 -> NPE -> NACK(카프카가 실제로 이걸받진않지만 관용적표현임) -> 메시지큐 offset 미전진
        // userId가 null이면 그대로 서비스로 내려가 저장 시점에 터진다.
        if (envelope.eventType() == null || envelope.userId() == null) {
            log.error("envelope 필수 필드 누락. 건너뛴다. eventType={} userId={}",
                    envelope.eventType(), envelope.userId());
            return;
        }

        // eventId는 여기서만 알 수 있다. 아래 서비스들의 로그에도 붙도록 MDC에 넣는다.
        MDC.put("eventId", String.valueOf(envelope.eventId()));
        MDC.put("userId", String.valueOf(envelope.userId()));
        try {
            dispatch(envelope); // 메서드 안에 찍히는 모든 로그에 eventId, userId 붙음
        } finally {
            MDC.remove("eventId"); // 서버나 메시지 컨슈머는 성능때문에 스레드 풀 방식으로 스레드 재사용
            // MDC를 지우지 않으면 다음 다른 메시지 처리할때 이전 메시지의 MDC 정보가 남아서 지워야함.
            MDC.remove("userId");
        }
    }

    private void dispatch(HealthEventEnvelope envelope) {
        switch (envelope.eventType()) {
            case HEALTH_ACTIVITY_SYNCED -> applySnapshot(envelope);
            case QUEST_SUGGESTED -> storeSuggestion(envelope);
            // MVP 범위 밖. 소비는 하되 아무것도 하지 않는다 -- 안 받으면 offset이 안 밀린다.
            case DAILY_GOAL_COMPLETED -> log.debug("DAILY_GOAL_COMPLETED는 MVP 범위 밖이라 무시한다.");
            default -> log.error("알 수 없는 eventType={}", envelope.eventType());
        }
    }
    // T1 입구
    private void applySnapshot(HealthEventEnvelope envelope) {
        HealthActivitySyncedData data = convert(envelope.data(), HealthActivitySyncedData.class);
        if (data == null) {
            return;
        }
        if (data.activityDate() == null || data.measuredAt() == null || data.cumulative() == null) {
            log.error("HEALTH_ACTIVITY_SYNCED 필수 필드 누락. 건너뛴다. data={}", envelope.data());
            return;
        }

        Snapshot snapshot = new Snapshot(
                data.activityDate(),
                data.measuredAt().toInstant(),
                data.cumulative().steps(),
                data.cumulative().activeMinutes(),
                data.cumulative().activeCalories()
        );

        Optional<ApplyResult> result = questProgressService.apply(envelope.userId(), snapshot);
        log.debug("HEALTH_ACTIVITY_SYNCED 개인 퀘스트 처리 완료. measuredAt={} result={}",
                snapshot.measuredAt(), result.map(Object::toString).orElse("NO_ACTIVE_QUEST"));

        // 같은 스냅샷을 파티 퀘스트에도 반영한다.
        // 두 서비스가 각자 트랜잭션을 연다. 하나로 묶지 않는 이유는 둘이 독립이기 때문이다.
        // 파티 쪽에서 문제가 생겼다고 개인 퀘스트 판정과 XP 지급을 되돌릴 이유가 없다.
        // 파티 쪽이 예외를 던지면 컨슈머가 이 이벤트를 다시 처리하는데,
        // 개인 퀘스트는 이미 반영한 시각 이하의 스냅샷을 무시하므로 두 번 반영되지 않는다.
        Optional<ContributionResult> partyResult =
                partyQuestProgressService.apply(envelope.userId(), snapshot);
        log.debug("HEALTH_ACTIVITY_SYNCED 파티 퀘스트 처리 완료. measuredAt={} result={}",
                snapshot.measuredAt(), partyResult.map(Object::toString).orElse("NO_ACTIVE_PARTY_QUEST"));
    }

    // T2 입구
    // 제안을 받아서 보관만 한다. 퀘스트는 여기서 만들지 않는다.
    // 유저가 Slack 카드에서 수락을 눌렀을 때 HTTP 로 들어와서 만들어진다.
    private void storeSuggestion(HealthEventEnvelope envelope) {
        QuestSuggestedData data = convert(envelope.data(), QuestSuggestedData.class);
        if (data == null) {
            return;
        }
        // 필수 필드 검사에서 title 추가
        if (data.suggestionId() == null || data.activityDate() == null || data.basedOnMeasuredAt() == null
        || data.title() == null || data.title().isEmpty()) {
            log.error("QUEST_SUGGESTED 필수 필드 누락. 건너뛴다. data={}", envelope.data());
            return;
        }

        String title = data.title();
        if (title.length() > MAX_TITLE_LENGTH) {
            // Health는 하루에 한번만 제안한다. 하지만 내쪽에서는 제안이 몇번이 오든
            // 활성화 퀘스트는 단 1개이다.
            log.warn("title이 {}자를 넘어 자른다. suggestionId={} length={}", MAX_TITLE_LENGTH,
                data.suggestionId(), title.length());
            title= clampTitle(title);
        }

        SuggestionOutcome outcome = questSuggestionService.store(new QuestSuggestionCommand(
                envelope.userId(),
                data.suggestionId(),
                data.activityDate(),
                data.basedOnMeasuredAt().toInstant(),
                title, // 앞에 지역변수에서 자른 값 대입
                data.metric(),
                data.targetValue()
        ));

        // 폐기 사유별 로그 레벨은 서비스가 정한다. 여기서는 eventId와의 연결만 남긴다.
        log.debug("QUEST_SUGGESTED 처리 완료. suggestionId={} outcome={}", data.suggestionId(), outcome);
    }

    private <T> T convert(JsonNode data, Class<T> type) {
        if (data == null || data.isNull()) {
            log.error("data가 비어 있다. 건너뛴다. type={}", type.getSimpleName());
            return null;
        }
        try {
            return objectMapper.treeToValue(data, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("data 변환 실패. 건너뛴다. type={} data={}", type.getSimpleName(), data, e);
            return null;
        }
    }

    private static String clampTitle(String title) {
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        String cut = title.substring(0, MAX_TITLE_LENGTH);
        // 서로게이트 쌍 중간을 자르면 깨진 문자가 남는다 (AI가 이모지를 붙이는 경우)
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

}
