package com.workoutdone.rpgym.game.quest.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;


// Health가 health.events 토픽에 싣는 공통 envelope(메시지형식설계에 속함)
// eventType을 enum이 아니라 String으로 받는다. 3종 밖의 값이 오면 역직렬화가 예외를 던지니깐
// 컨슈머에서 예외는 offset 미커밋 -> 무한 재시도를 함. 알 수 없는 타입은 "처리했고 결론이 무시".
// data를 JsonNode로 두는 이유는 eventType을 읽기 전에는 어떤 타입인지 알 수 없기 때문이다.
// 분기한 뒤 각 data 타입으로 변환한다.(카프카의 성능과 사용편의성에 영향을 주는 코드)
// occurredAt은 일부러 받지 않는다. 발행 시각이라 재전송하면 바뀌므로 판정(도메인레벨)에서 이미 필드로 존재
// 판정 기준은 data(객체) 안의 measuredAt이다.
// 즉 여기서 핵심은

@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthEventEnvelope(
        UUID eventId,
        String eventType,
        UUID userId,
        JsonNode data
) {
}
