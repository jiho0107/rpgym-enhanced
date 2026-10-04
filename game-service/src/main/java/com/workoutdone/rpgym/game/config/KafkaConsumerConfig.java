package com.workoutdone.rpgym.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// 사실 이 프로젝트는 공부를 하면 할수록 느낀건 굳이 카프카를 쓸 근거가 부족하긴하다.(rabbitMQ로 대체가 더 맞을수도 있다)
// userID로 유저별 순서 보장은 사실상 정합성의 근거는 되지않는다.
// 10:30분 데이터와 10:00데이터가 어떤 오류때문에 거꾸로 도착해도 어짜피 워터마크로 인해 STALE로 무시된다.
// 그리고 리펙토링이 진행되기전 결과로 냈던 자료및 코드는 보관 + 오프셋 되감기로 재처리가 없었다.
// 장애 후 재소비 + DLQ -> 재발행을 현재 진행할 예정이다.(2026-10-02).
// 즉 현재 지금 이 주석을 쓰는 순간에는 메시지 단위 실패 처리가 없기때문에
// 오프셋을 못넘김으로써 파티션 전체가 멈추는 현상이 일어난다.(이걸 HOL Blocking이라고 한다)
// 또한 카프카를 쓰는 이유가 트래픽이 많고 처치량이 많은 환경에서만 적합한데 현재 프로젝트에서 근거로서는
// 매우 부족하다. 내가 생각하기엔 이건 오버엔지니어링이다. 하지만 학습을 위해서 써봣다고 경험치적 관점으로
// 쓴다고 생각하고 실험을 어떻게 하냐에 따라 한계를 실험해볼수있지않을까?
// spike Test 역시 병목은 DB에서 일어났다.
// 그럼 오버엔지니어링이란 관점에서 무엇을 얻었나?라고함면
// 여러 서비스가 같은 이벤트를 독립적으로 각자의 리듬으로 소비하고, 장애 후 되감아 재처리할수 있다는 장점은
// 아직 유효하다.(결국 결과적일관성을 얼마나 지키냐가 핵심이긴하다)
@Configuration
public class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 2_000L;

    // 반드시 하나 알아가야할것이 또 존재한다.
    // 지금 코드상 방어 로직은 정합성만을 기준으로 막는다.
    // 예상치 못한 결정적 실패(NPE,내가 생각지도 못한 데이터 조합, 버그로 인한 제약위반)은
    // 어짜피 항상 실패한다. 이걸 DLQ로 내려보내야하는걸 어떻게 걸르는지가 DLQ 설계의 핵심이다.
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
