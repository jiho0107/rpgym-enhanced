package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.config.AsyncConfig;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * AsyncConfig + @Async가 실제로 붙어서, 중복 클릭 카드 갱신이 클릭 요청 스레드를 붙잡지 않는지 확인한다.
 * (스프링 프록시를 거쳐야만 비동기로 실행되므로 최소한의 스프링 컨텍스트를 띄운다)
 */
@SpringJUnitConfig(classes = {AsyncConfig.class, QuestOfferCardUpdater.class})
class QuestOfferCardUpdaterAsyncTest {

    private static final QuestOfferCard CARD = new QuestOfferCard("[]", "텍스트");

    @Autowired
    private QuestOfferCardUpdater updater;

    @MockitoBean
    private SlackApiClient slackApiClient;

    @Test
    @DisplayName("카드 갱신은 전용 스레드 풀(slack-card-)에서 실행되고, 호출한 쪽은 Slack 응답을 기다리지 않고 바로 돌아온다")
    void runsOnDedicatedExecutorWithoutBlockingCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        // Slack 호출이 오래 걸리는 상황을 흉내낸다 -- release가 풀릴 때까지 막혀 있다.
        doAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            release.await(5, TimeUnit.SECONDS);
            finished.countDown();
            return null;
        }).when(slackApiClient).updateMessage(any(), any(), any(), any());

        long start = System.nanoTime();
        updater.updateDuplicateClickCard("D0123456789", "1732147300.000300", null, CARD);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Slack 호출이 아직 막혀 있는데도 호출이 끝났다 = 기다리지 않았다
        assertThat(finished.getCount()).isEqualTo(1);
        assertThat(elapsedMillis).isLessThan(1000);

        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("slack-card-");
        assertThat(threadName.get()).isNotEqualTo(Thread.currentThread().getName());
    }
}
