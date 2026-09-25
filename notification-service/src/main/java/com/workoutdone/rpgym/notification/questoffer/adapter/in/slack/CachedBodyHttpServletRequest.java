package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Slack 서명 검증은 필터 체인 맨 앞에서 미리 읽어둔 원본 바이트가 필요하다.
 * 생성 시점에 원본 body 바이트를 받아 보관해두고, 이후 어떤 컴포넌트가 getInputStream()/getReader()로 body를 다시 읽으려 해도
 * 항상 이 캐싱된 원본 바이트를 그대로 돌려준다 -- form 파라미터 파싱 등으로 재구성된 값이 아니다.
 * Spring/Tomcat 내부의 다른 컴포넌트가 body를 재구성해서 원본과 달라지는 걸 막기 위해 사용한다.
 * SlackRawBodyFilter가 쥐고 있는 원본 바이트를, 이후 체인에 있는 다른 모든 컴포넌트한테 "이게 진짜 요청 body야"라고 계속 나눠주는 창구 역할을 한다.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
        super(request);
        this.cachedBody = cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("비동기 읽기는 쓰지 않는다");
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
