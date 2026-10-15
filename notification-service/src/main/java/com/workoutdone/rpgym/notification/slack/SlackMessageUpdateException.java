package com.workoutdone.rpgym.notification.slack;

/**
 * 이미 보낸 Slack 메시지를 고치지 못했을 때(chat.update / response_url).
 * 발송 실패(SlackMessageSendException)와 나눈 이유
 * -- 수정 실패는 처리 결과 자체에는 영향이 없고, 호출하는 쪽이 이 예외를 잡아 다음 갱신 수단(chat.update -> response_url)으로 넘어가야 하기 때문이다.
 */
public class SlackMessageUpdateException extends RuntimeException {

    public SlackMessageUpdateException(String message) {
        super(message);
    }

    public SlackMessageUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
