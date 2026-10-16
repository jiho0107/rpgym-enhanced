package com.workoutdone.rpgym.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 갱신 전용 풀과 스프링 부트 기본 공용 풀이 나뉘어 있는지 확인한다.
 *
 * AsyncConfig처럼 executor를 직접 등록하면 기본값(spring.task.execution.mode=auto)에서는 기본 공용 풀이 만들어지지 않아,
 * 이름 없는 @Async까지 카드 갱신 풀로 가서 섞인다. application.yml의 mode=force가 이를 막는지 본다.
 * (@SpringBootTest라 실제 application.yml을 읽는다. 필요한 자동 설정만 가져와 Kafka/DB 등은 띄우지 않는다)
 */
@SpringBootTest(
        classes = {AsyncConfig.class, AsyncExecutorSeparationTest.AsyncProbe.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration(TaskExecutionAutoConfiguration.class)
class AsyncExecutorSeparationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AsyncProbe probe;

    @Test
    @DisplayName("카드 갱신 전용 풀과 함께 스프링 부트 기본 공용 풀(applicationTaskExecutor)도 만들어진다")
    void bothExecutorsExist() {
        assertThat(context.containsBean(AsyncConfig.SLACK_CARD_UPDATE_EXECUTOR)).isTrue();
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
    }

    @Test
    @DisplayName("이름 없이 붙인 @Async는 기본 공용 풀에서 실행되고, 카드 갱신 풀(slack-card-)로 가지 않는다")
    void unnamedAsyncDoesNotUseCardUpdatePool() throws Exception {
        String threadName = probe.unnamed().get(5, TimeUnit.SECONDS);

        assertThat(threadName).doesNotStartWith("slack-card-");
        assertThat(threadName).startsWith("task-"); // 스프링 부트 기본 공용 풀의 스레드 이름
    }

    @Test
    @DisplayName("이름을 붙인 @Async(slackCardUpdateExecutor)는 카드 갱신 전용 풀에서 실행된다")
    void namedAsyncUsesCardUpdatePool() throws Exception {
        String threadName = probe.cardUpdate().get(5, TimeUnit.SECONDS);

        assertThat(threadName).startsWith("slack-card-");
    }

    /** 어느 풀의 스레드에서 실행됐는지 이름을 돌려주는 테스트용 빈 */
    @Component
    static class AsyncProbe {

        @Async
        public CompletableFuture<String> unnamed() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

        @Async(AsyncConfig.SLACK_CARD_UPDATE_EXECUTOR)
        public CompletableFuture<String> cardUpdate() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
