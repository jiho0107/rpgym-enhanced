package com.workoutdone.rpgym.gateway.infrastructure.security;

import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.common.jwt.AccessTokenBlacklistKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccessTokenBlacklistFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {
        return exchange.getPrincipal()
                .cast(Authentication.class)
                .map(Authentication::getPrincipal)
                .cast(Jwt.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optionalJwt -> {
                    if (optionalJwt.isEmpty()) {
                        return chain.filter(exchange);
                    }

                    Jwt jwt = optionalJwt.get();
                    String jti = jwt.getId();

                    if (jti == null || jti.isBlank()) {
                        return chain.filter(exchange);
                    }

                    return redisTemplate.hasKey(
                                    AccessTokenBlacklistKey.of(jti)
                            )
                            .timeout(Duration.ofMillis(500))
                            .flatMap(blacklisted -> {
                                if (blacklisted) {
                                    return errorResponseWriter.write(
                                            exchange,
                                            CommonErrorCode.UNAUTHORIZED
                                    );
                                }

                                return chain.filter(exchange);
                            })
                            .onErrorResume(ex -> {
                                log.warn(
                                        "Access Token Blacklist 조회 실패 - fail-open 처리. jti={}",
                                        jti,
                                        ex
                                );
                                return chain.filter(exchange);
                            });
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}