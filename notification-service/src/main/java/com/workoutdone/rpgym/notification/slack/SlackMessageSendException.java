package com.workoutdone.rpgym.notification.slack;

/**
 * Slack API 호출 실패를 unchecked로 감싼다.
 * Kafka 컨슈머가 이 예외를 잡지 않고 흘려보내면 offset이 커밋되지 않아 재시도된다 --
 * Slack 발송 실패의 현실적인 원인(네트워크, Slack 쪽 일시 장애)은 재시도로 해소되는 것들이다.
 */
public class SlackMessageSendException extends RuntimeException {

    public SlackMessageSendException(String message) {
        super(message);
    }

    public SlackMessageSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
