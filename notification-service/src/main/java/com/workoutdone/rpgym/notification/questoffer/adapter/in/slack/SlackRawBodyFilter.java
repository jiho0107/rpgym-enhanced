package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Slack 서명 검증에는 Tomcat/Spring의 다른 어떤 컴포넌트도 아직 건드리지 않은 원본 바이트가 필요하다.
 * (form 파라미터 파싱 등을 거치면 URLEncoder 규칙상 '*' 같은 일부 문자가 재인코딩 없이 풀려서
 * 원본과 달라지는 걸 직접 확인했다 -- 이 필터로 필터 체인 맨 앞에서 원본을 먼저 캐싱해서 우회한다.)
 *
 * FilterConfig에서 이 경로에 대해서만 HIGHEST_PRECEDENCE로 등록해서, 다른 어떤 필터보다도 먼저 실행되게 한다.
 * 요청이 들어오자마자(다른 어떤 컴포넌트도 건드리기 전) request.getInputStream().readAllBytes()로 원본 바이트를 통째로 읽어서 request attribute(slackRawBodyBytes)에 저장
 * 이후 필터 체인은 CachedBodyHttpServletRequest로 감싼 요청으로 진행시킴
 */
public class SlackRawBodyFilter extends OncePerRequestFilter {

    public static final String RAW_BODY_ATTRIBUTE = "slackRawBodyBytes";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();
        request.setAttribute(RAW_BODY_ATTRIBUTE, rawBody);
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, rawBody), response);
    }
}
