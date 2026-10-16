package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.config.AsyncConfig;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;

import brave.Tracing;
import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.ThreadLocalCurrentTraceContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
 * 클릭 요청 스레드의 trace 정보(traceId/spanId)가 카드 갱신 스레드(slack-card-)의 로그 MDC까지 이어지는지 확인한다.
 *
 * 앱에서는 스프링 부트가 Micrometer Tracing(Brave)을 구성하지만, 여기서는 같은 구성 요소를 직접 만든다:
 *   Brave(MDC에 traceId/spanId를 넣는 MDCScopeDecorator 포함) -> Micrometer Tracer -> ObservationRegistry
 * ContextPropagatingTaskDecorator는 ObservationThreadLocalAccessor를 통해 "지금 열려 있는 Observation"을 넘기므로,
 * 그 접근자가 이 테스트의 ObservationRegistry를 보도록 연결해 둔다.
 */
@SpringJUnitConfig(classes = {AsyncConfig.class, QuestOfferCardUpdater.class})
class QuestOfferCardUpdaterTracePropagationTest {

    private static final QuestOfferCard CARD = new QuestOfferCard("[]", "텍스트");

    private static Tracing tracing;
    private static ObservationRegistry observationRegistry;
    private static ObservationRegistry previousRegistry;

    @Autowired
    private QuestOfferCardUpdater updater;

    @MockitoBean
    private SlackApiClient slackApiClient;

    @BeforeAll
    static void setUpTracing() {
        ThreadLocalCurrentTraceContext braveContext = ThreadLocalCurrentTraceContext.newBuilder()
                .addScopeDecorator(MDCScopeDecorator.get())
                .build();
        tracing = Tracing.newBuilder().currentTraceContext(braveContext).build();
        BraveTracer tracer = new BraveTracer(
                tracing.tracer(), new BraveCurrentTraceContext(braveContext), new BraveBaggageManager());

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));

        ObservationThreadLocalAccessor accessor = ObservationThreadLocalAccessor.getInstance();
        previousRegistry = accessor.getObservationRegistry();
        accessor.setObservationRegistry(observationRegistry);
    }

    @AfterAll
    static void tearDownTracing() {
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(previousRegistry);
        tracing.close();
    }

    @Test
    @DisplayName("클릭 요청 스레드의 traceId/spanId가 카드 갱신 스레드의 MDC에도 그대로 들어간다")
    void tracingContextIsPropagatedToCardUpdateThread() throws Exception {
        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncSpanId = new AtomicReference<>();
        AtomicReference<String> asyncThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            // 카드 갱신 스레드 안에서 로그가 찍히는 시점의 MDC 값을 잡아둔다
            asyncTraceId.set(MDC.get("traceId"));
            asyncSpanId.set(MDC.get("spanId"));
            asyncThread.set(Thread.currentThread().getName());
            done.countDown();
            return null;
        }).when(slackApiClient).updateMessage(any(), any(), any(), any());

        // Slack 클릭 요청을 처리하는 동안 열려 있는 trace를 흉내낸다
        Observation click = Observation.start("slack.interaction", observationRegistry);
        String callerTraceId;
        String callerSpanId;
        try (Observation.Scope ignored = click.openScope()) {
            callerTraceId = MDC.get("traceId");
            callerSpanId = MDC.get("spanId");

            updater.updateDuplicateClickCard("D0123456789", "1732147300.000300", null, CARD);

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            click.stop();
        }

        assertThat(callerTraceId).as("호출 스레드에는 trace가 열려 있어야 한다").isNotBlank();
        assertThat(asyncThread.get()).startsWith("slack-card-");
        assertThat(asyncTraceId.get()).isEqualTo(callerTraceId);
        assertThat(asyncSpanId.get()).isEqualTo(callerSpanId);
    }

    @Test
    @DisplayName("trace가 없는 호출에서는 카드 갱신 스레드의 MDC에 이전 작업의 traceId가 남아 있지 않다")
    void noStaleTraceIdLeaksIntoPooledThread() throws Exception {
        AtomicReference<String> asyncTraceId = new AtomicReference<>("not-captured");
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            asyncTraceId.set(MDC.get("traceId"));
            done.countDown();
            return null;
        }).when(slackApiClient).updateMessage(any(), any(), any(), any());

        // 앞 테스트에서 같은 풀 스레드가 trace를 들고 실행됐더라도, 작업이 끝나면 지워져야 한다
        updater.updateDuplicateClickCard("D0123456789", "1732147300.000300", null, CARD);

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(asyncTraceId.get()).isNull();
    }
}
