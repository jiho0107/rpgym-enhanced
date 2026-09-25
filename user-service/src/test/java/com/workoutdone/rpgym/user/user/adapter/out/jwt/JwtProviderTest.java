package com.workoutdone.rpgym.user.user.adapter.out.jwt;

import com.workoutdone.rpgym.common.jwt.JwtSecretKeyFactory;
import com.workoutdone.rpgym.common.security.UserRole;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    // HS256은 256비트(32바이트) 이상의 키가 필요하다
    private static final String SECRET = "unit-test-secret-key-for-jwt-provider-0123456789";
    private static final String OTHER_SECRET = "another-secret-key-for-jwt-provider-test-0123456789";
    private static final long EXPIRY_SECONDS = 1800L;

    private final JwtProvider jwtProvider = new JwtProvider(SECRET, EXPIRY_SECONDS);

    @Test
    @DisplayName("발급한 Access Token에는 UUID 형식의 jti가 포함된다")
    void createAccessToken_includesJti() {
        String token = jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.USER);

        AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

        assertThat(claims.jti()).isNotBlank();
        assertThat(UUID.fromString(claims.jti())).isNotNull();
    }

    @Test
    @DisplayName("같은 사용자에게 여러 번 발급해도 jti는 토큰마다 다르다(로그아웃 시 토큰별로 식별하기 위함)")
    void createAccessToken_generatesDifferentJtiEachTime() {
        UUID userId = UUID.randomUUID();

        AccessTokenClaims first = jwtProvider.parseAccessToken(jwtProvider.createAccessToken(userId, UserRole.USER));
        AccessTokenClaims second = jwtProvider.parseAccessToken(jwtProvider.createAccessToken(userId, UserRole.USER));

        assertThat(first.jti()).isNotEqualTo(second.jti());
    }

    @Test
    @DisplayName("파싱한 만료 시각은 설정된 Access Token 만료 시간과 일치한다")
    void parseAccessToken_returnsConfiguredExpiry() {
        Instant before = Instant.now();

        String token = jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.USER);
        AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

        // JWT의 exp는 초 단위로 저장되므로 1~2초 오차를 허용
        assertThat(claims.expiresAt())
                .isBetween(before.plusSeconds(EXPIRY_SECONDS - 2), Instant.now().plusSeconds(EXPIRY_SECONDS + 2));
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰이면 JwtException이 발생한다")
    void parseAccessToken_wrongSignature_throwsJwtException() {
        String forged = new JwtProvider(OTHER_SECRET, EXPIRY_SECONDS)
                .createAccessToken(UUID.randomUUID(), UserRole.USER);

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("이미 만료된 토큰이면 ExpiredJwtException이 발생한다")
    void parseAccessToken_expired_throwsExpiredJwtException() {
        String expired = new JwtProvider(SECRET, -10).createAccessToken(UUID.randomUUID(), UserRole.USER);

        assertThatThrownBy(() -> jwtProvider.parseAccessToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("jti가 없는 토큰(Blacklist 도입 이전에 발급된 토큰)은 jti가 null인 결과를 반환한다")
    void parseAccessToken_tokenWithoutJti_returnsNullJti() {
        String legacyToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(EXPIRY_SECONDS)))
                .signWith(JwtSecretKeyFactory.create(SECRET), Jwts.SIG.HS256)
                .compact();

        AccessTokenClaims claims = jwtProvider.parseAccessToken(legacyToken);

        assertThat(claims.jti()).isNull();
        assertThat(claims.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("토큰이 null이면 IllegalArgumentException이 발생한다(LogoutService는 이 예외를 무시하고 로그아웃을 성공 처리)")
    void parseAccessToken_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> jwtProvider.parseAccessToken(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
