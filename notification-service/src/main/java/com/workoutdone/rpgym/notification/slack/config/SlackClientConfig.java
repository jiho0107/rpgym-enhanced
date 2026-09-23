package com.workoutdone.rpgym.notification.slack.config;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.MethodsClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackClientConfig {

    /**
     * Slack SDK 기본 설정인 OkHttp 기본값(10초)은 Kafka 컨슈머 스레드를 너무 오래 묶어둘 수 있어 줄인다.
     * 실패하면 재시도하지 않고 그대로 예외를 던져 Kafka가 메시지 전체를 재시도하게 한다 --
     * 로컬에서 재시도하면 "응답만 늦게 온 성공"을 중복 발송으로 만들 위험이 있다.
     */
    private static final int HTTP_CALL_TIMEOUT_MILLIS = 3000; //호출 전체(연결+요청 전송+응답 수신을 다 합쳐서) 시간

    /*
    Slack Java SDK를 쓰려면 Bot Token을 들고 있는 MethodsClient 객체가 필요한데
    애플리케이션 전체에서 하나만 만들어서(싱글톤) 재사용하기 위해 @Bean으로 등록
     */
    @Bean
    public MethodsClient slackMethodsClient(@Value("${rpgym.slack.bot-token}") String botToken) {
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(HTTP_CALL_TIMEOUT_MILLIS);
        return Slack.getInstance(config).methods(botToken);
    }
}
