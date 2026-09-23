package com.workoutdone.rpgym.health.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // "지금"을 주입 가능하게 만든다.
    // Quest 제안 날짜 가드(#139)처럼 "오늘 날짜"와 비교하는 로직을
    // Instant.now()로 코드에 박으면 테스트에서 원하는 시각을 통제할 방법이 없다.
    // 테스트에서는 Clock.fixed()를 넣는다.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
