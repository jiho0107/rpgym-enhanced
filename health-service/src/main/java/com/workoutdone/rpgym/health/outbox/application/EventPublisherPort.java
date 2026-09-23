package com.workoutdone.rpgym.health.outbox.application;

import com.workoutdone.rpgym.health.outbox.domain.HealthEventType;

/**
 * 발행기가 메시지 브로커로 나가는 통로. 구현은 어댑터가 담당한다.
 *
 * payload는 Outbox에 저장된 문자열을 그대로 넘긴다. 이 계층에서
 * 역직렬화하거나 재구성하지 않는다 — eventId가 바뀌면 Game Service의
 * 중복 처리 방지 키가 무력화된다.
 */
public interface EventPublisherPort {

    /**
     * @param partitionKey Kafka 메시지 key (= userId). 같은 사용자 이벤트의 순서를 보장한다.
     * @param eventType    Kafka 헤더로 실린다. health.events에는 두 종류가 함께 실리므로
     *                     컨슈머가 payload 역직렬화 없이 타입으로 필터링할 수 있게 한다.
     */
    void publish(String topic, String partitionKey, String payload, HealthEventType eventType);

    /** 최대 재시도를 넘긴 이벤트를 DLQ로 보낸다. payload 원문은 그대로 두고 진단 정보만 헤더로 싣는다. */
    void publishToDlq(String dlqTopic,
                      String partitionKey,
                      String payload,
                      HealthEventType eventType,
                      int retryCount,
                      String errorMessage);
}