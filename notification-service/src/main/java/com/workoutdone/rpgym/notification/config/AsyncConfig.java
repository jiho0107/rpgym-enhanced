package com.workoutdone.rpgym.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Slack 카드 갱신을 클릭 요청 스레드와 떼어 실행하기 위한 비동기 설정.
 *
 * 중복 클릭 카드 갱신은 "표시만 맞추는" 작업이라 Slack 3초 응답 제한 안에서 기다릴 이유가 없다.
 * 서버가 죽어 갱신이 사라져도 처리 결과에는 영향이 없고, 카드에 버튼이 남아 있으니 다시 누르면 다시 시도된다.
 * 그래서 DB에 남기지 않고 메모리 스레드 풀로 처리한다.
 *
 * 다른 작업과 섞이지 않도록 기본 executor가 아니라 이름을 붙인 전용 풀을 쓴다.
 * 같은 풀을 쓰면 다른 작업이 몰릴 때 카드 갱신이 대기줄 뒤로 밀리고(반대도 마찬가지), 크기·거절 정책·trace 전파를 이 작업에 맞게 정할 수 없다.
 * 단, executor를 직접 등록하면 스프링 부트가 기본 공용 풀을 만들지 않아 이름 없는 @Async까지 이 풀로 온다.
 * 그래서 application.yml에 spring.task.execution.mode=force를 둬서 기본 공용 풀을 함께 살려 두고,
 * 이름 없는 @Async는 그쪽(applicationTaskExecutor)으로 가게 한다.
 *
 * MDC는 스레드마다 따로라서, 그냥 넘기면 카드 갱신 로그에 traceId/spanId가 비어 클릭 요청 로그와 묶이지 않는다.
 * ContextPropagatingTaskDecorator가 호출 스레드의 트레이싱 컨텍스트를 작업과 함께 넘기고,
 * 새 스레드에서 복원되면 Micrometer Tracing이 MDC에 traceId/spanId를 다시 채운다.
 * 직접 만든 풀에는 스프링 부트가 이 설정을 붙여주지 않아서 여기서 넣는다.
 *  Kafka 컨슈머(GameEventConsumer)가 MDC.put()으로 직접 넣은 값(eventId 등)은 넘어가지 않는다 -- 카드 갱신은 HTTP 클릭에서 시작해 원래 eventId가 없다
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String SLACK_CARD_UPDATE_EXECUTOR = "slackCardUpdateExecutor";

    @Bean(name = SLACK_CARD_UPDATE_EXECUTOR)
    public ThreadPoolTaskExecutor slackCardUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); //기본으로 유지할 스레드 수(2개) -> 작업이 들어오면 스레드를 우선 2개까지 사용함(작업 2개를 동시에 실행할 수 있음)
        executor.setMaxPoolSize(4); //최대 작업 인원(4개) -> 스레드를 최대 4개까지 늘릴 수 있다
        executor.setQueueCapacity(100); //대기 공간(100개) -> 기본 스레드가 모두 작업 중이면, 새 작업을 최대 100개까지 큐에 보관함
        executor.setThreadNamePrefix("slack-card-"); //스레드 이름 앞에 붙일 값
        // 큐까지 가득 차면 로그만 남기고 작업을 버린다. 호출한 스레드(클릭 요청)에서 대신 실행하면 Slack 3초 응답을 넘길 수 있다.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Slack 카드 갱신 작업이 밀려 버렸다. 사용자가 다시 누르면 다시 시도된다."));
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator()); //다른 스레드에 로그·추적 정보 전달
        executor.setWaitForTasksToCompleteOnShutdown(true); //정상 종료 시 남은 작업 처리 방식 설정
        executor.setAwaitTerminationSeconds(5); //종료 과정에서 최대 5초 기다림
        return executor;
    }
}
