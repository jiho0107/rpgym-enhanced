package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SlackRawBodyFilterConfig {

    // 다른 모든 필터(FormContentFilter, CharacterEncodingFilter 등)보다 먼저 실행되도록
    // HIGHEST_PRECEDENCE로 등록하고, Slack 콜백 경로에만 적용한다.
    @Bean
    public FilterRegistrationBean<SlackRawBodyFilter> slackRawBodyFilter() {
        FilterRegistrationBean<SlackRawBodyFilter> registration = new FilterRegistrationBean<>(new SlackRawBodyFilter());
        registration.addUrlPatterns("/api/v1/notifications/slack/interactions");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
