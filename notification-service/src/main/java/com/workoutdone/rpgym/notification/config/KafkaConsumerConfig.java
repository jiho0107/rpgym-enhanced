package com.workoutdone.rpgym.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 2_000L;

    /**
     * 재시도 횟수를 두지 않는다.
     *
     * 기본 동작은 10회 시도 후 로그를 남기고 offset을 커밋한다.
     * Slack/user-service가 20초만 내려가 있어도 그 Quest 제안 알림을 영영 건너뛴다는 뜻이고, 그것이 곧 유실이다.
     *
     * 여기까지 예외가 올라왔다는 것은 GameEventConsumer가 "다시 하면 되는 것"으로 판단했다는 뜻이므로
     * (계약 위반은 컨슈머 안에서 로그만 남기고 정상 종료한다) 될 때까지 재시도하는 것이 맞다.
     *
     * 대가는 그 파티션이 멈추는 것이다.
     * 같은 유저의 뒤 이벤트도 함께 멈추지만, 그 편이 순서를 건너뛴 채 진행하는 것보다 안전하다.
     *
     * 백오프가 max.poll.interval.ms를 넘겨 리밸런싱이 나도 offset이 커밋되지 않았으므로
     * 새 컨슈머가 같은 offset부터 다시 읽는다. 어느 쪽으로 가도 유실은 없다.
     *
     * game-service의 KafkaConsumerConfig와 동일한 설정이다 --
     * GameEventConsumer가 예외를 던지느냐 마느냐를 game-service의 HealthEventConsumer와 같은 원칙으로 판단하고 있으므로,
     * 그 판단을 실제로 뒷받침하는 이 에러 핸들러 설정도 동일하게 맞춘다.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
