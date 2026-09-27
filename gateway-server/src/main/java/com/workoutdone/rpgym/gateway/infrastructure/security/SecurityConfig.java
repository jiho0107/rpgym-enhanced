package com.workoutdone.rpgym.gateway.infrastructure.security;

import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.common.jwt.JwtClaimConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        return http
                // CSRF 비활성화
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // 보안 정책 설정
                .authorizeExchange(exchange -> exchange
                        // 인증 없이 접근 가능한 API
                        // actuator는 management 포트(19091)로 분리했지만 이 SecurityWebFilterChain이
                        // 포트 구분 없이 그대로 적용되는 걸 확인해서 유지함.
                        // 실제 보호는 인증이 아니라 네트워크 격리(19091은 expose만 있고 외부/nginx에서 접근 불가)로 이뤄짐
                        .pathMatchers(
                                "/api/v1/users/login",
                                "/api/v1/users/signup",
                                "/api/v1/users/refresh",
                                "/api/v1/notifications/slack/interactions",
                                "/actuator/health",
                                "/actuator/prometheus",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**"
                        ).permitAll()

                        .pathMatchers(
                                "/api/v1/internal/**"
                        ).denyAll()

                        // 그 외 모든 API는 인증 필요
                        // Spring Security가 인증 정보를 기반으로 인가 처리
                        .anyExchange().authenticated()
                )

                // JWT 기반 인증 설정
                // ReactiveJwtDecoder를 사용해서 Bearer Access Token을 JWT로 검증
                // 검증된 JWT의 role Claim을 Spring Security 권한으로 변환
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                )

                // 인증/인가 예외 처리
                .exceptionHandling(exception -> exception
                        // 인증 실패 시 공통 ErrorResponse 형식으로 401 응답
                        .authenticationEntryPoint(
                                (exchange, ex) -> errorResponseWriter.write(
                                        exchange,
                                        CommonErrorCode.UNAUTHORIZED
                                )
                        )

                        // 인가 실패 시 공통 ErrorResponse 형식으로 403 응답
                        .accessDeniedHandler(
                                (exchange, ex) -> errorResponseWriter.write(
                                        exchange,
                                        CommonErrorCode.FORBIDDEN
                                )
                        )
                )
                .build();
    }

    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

        // 필수 Claim 검증을 통과한 JWT의 role을
        // Spring Security의 GrantedAuthority로 변환
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtClaimConstants.ROLE);

            // role은 RequiredClaimsValidator에서 이미 검증되었으므로
            // 여기서는 별도의 null 검증 없이 권한으로 변환
            return List.of(
                    new SimpleGrantedAuthority("ROLE_" + role)
            );
        });

        // WebFlux 환경에서 사용할 수 있도록 Reactive Converter로 변환
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}