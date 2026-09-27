package com.workoutdone.rpgym.gateway.infrastructure.security;

import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.common.jwt.AccessTokenBlacklistKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccessTokenBlacklistFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private SecurityErrorResponseWriter errorResponseWriter;
    private GatewayFilterChain chain;

    private AccessTokenBlacklistFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        errorResponseWriter = mock(SecurityErrorResponseWriter.class);
        chain = mock(GatewayFilterChain.class);

        filter = new AccessTokenBlacklistFilter(
                redisTemplate,
                errorResponseWriter
        );
    }

    @Test
    void blacklistFilter는_authenticatedUserHeaderFilter보다_먼저_실행된다() {
        AuthenticatedUserHeaderFilter headerFilter =
                new AuthenticatedUserHeaderFilter();

        assertThat(filter.getOrder())
                .isLessThan(headerFilter.getOrder());
    }

    @Test
    void blacklist에_등록된_accessToken이면_401을_반환한다() {
        // given
        String jti = "blacklisted-jti";

        ServerWebExchange exchange = authenticatedExchange(jti);

        when(redisTemplate.hasKey(AccessTokenBlacklistKey.of(jti)))
                .thenReturn(Mono.just(true));

        when(errorResponseWriter.write(
                exchange,
                CommonErrorCode.UNAUTHORIZED
        )).thenReturn(Mono.empty());

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verify(redisTemplate)
                .hasKey(AccessTokenBlacklistKey.of(jti));

        verify(errorResponseWriter)
                .write(exchange, CommonErrorCode.UNAUTHORIZED);

        verify(chain, never())
                .filter(any());
    }

    @Test
    void blacklist에_없는_accessToken이면_요청을_통과시킨다() {
        // given
        String jti = "normal-jti";

        ServerWebExchange exchange = authenticatedExchange(jti);

        when(redisTemplate.hasKey(AccessTokenBlacklistKey.of(jti)))
                .thenReturn(Mono.just(false));

        when(chain.filter(exchange))
                .thenReturn(Mono.empty());

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verify(redisTemplate)
                .hasKey(AccessTokenBlacklistKey.of(jti));

        verify(chain)
                .filter(exchange);

        verifyNoInteractions(errorResponseWriter);
    }

    @Test
    void jti가_없으면_blacklist를_조회하지_않고_통과한다() {
        // given
        ServerWebExchange exchange = authenticatedExchange(null);

        when(chain.filter(exchange))
                .thenReturn(Mono.empty());

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain)
                .filter(exchange);
    }

    @Test
    void authentication이_없으면_blacklist를_조회하지_않고_통과한다() {
        // given
        ServerWebExchange exchange =
                MockServerWebExchange.from(
                        org.springframework.mock.http.server.reactive
                                .MockServerHttpRequest
                                .get("/api/v1/users/login")
                                .build()
                );

        when(chain.filter(exchange))
                .thenReturn(Mono.empty());

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain)
                .filter(exchange);
    }

    @Test
    void redis_조회에_실패하면_failOpen으로_요청을_통과시킨다() {
        // given
        String jti = "redis-error-jti";

        ServerWebExchange exchange = authenticatedExchange(jti);

        when(redisTemplate.hasKey(AccessTokenBlacklistKey.of(jti)))
                .thenReturn(Mono.error(
                        new RuntimeException("Redis connection failed")
                ));

        when(chain.filter(exchange))
                .thenReturn(Mono.empty());

        // when
        Mono<Void> result = filter.filter(exchange, chain);

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verify(redisTemplate)
                .hasKey(AccessTokenBlacklistKey.of(jti));

        verify(chain)
                .filter(exchange);
    }

    private ServerWebExchange authenticatedExchange(String jti) {
        Jwt.Builder jwtBuilder = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject("550e8400-e29b-41d4-a716-446655440000")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .claims(claims ->
                        claims.putAll(
                                Map.of("role", "USER")
                        )
                );

        if (jti != null) {
            jwtBuilder.claim("jti", jti);
        }

        Jwt jwt = jwtBuilder.build();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        jwt,
                        null
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        org.springframework.mock.http.server.reactive
                                .MockServerHttpRequest
                                .get("/api/v1/test")
                                .build()
                );

        return exchange.mutate()
                .principal(Mono.just(authentication))
                .build();
    }
}