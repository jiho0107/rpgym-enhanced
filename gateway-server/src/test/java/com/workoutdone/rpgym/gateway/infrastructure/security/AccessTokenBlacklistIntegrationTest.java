package com.workoutdone.rpgym.gateway.infrastructure.security;

import com.workoutdone.rpgym.common.jwt.AccessTokenBlacklistKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false"
        }
)
class AccessTokenBlacklistIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @Test
    void springSecurity에서_검증된_Jwt의_jti로_blacklist를_조회한다() {
        // given
        String accessToken = "valid-access-token";
        String jti = "integration-test-jti";

        Jwt jwt = Jwt.withTokenValue(accessToken)
                .header("alg", "HS256")
                .subject("550e8400-e29b-41d4-a716-446655440000")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .claims(claims -> claims.putAll(
                        Map.of(
                                "role", "USER",
                                "jti", jti
                        )
                ))
                .build();

        when(jwtDecoder.decode(accessToken))
                .thenReturn(Mono.just(jwt));

        when(redisTemplate.hasKey(
                AccessTokenBlacklistKey.of(jti)
        )).thenReturn(Mono.just(true));

        // when & then
        webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers ->
                        headers.setBearerAuth(accessToken)
                )
                .exchange()
                .expectStatus()
                .isUnauthorized();

        verify(jwtDecoder)
                .decode(accessToken);

        verify(redisTemplate)
                .hasKey(AccessTokenBlacklistKey.of(jti));
    }
}