package com.workoutdone.rpgym.user.user.adapter.out.jwt;

import com.workoutdone.rpgym.common.jwt.JwtClaimConstants;
import com.workoutdone.rpgym.common.jwt.JwtSecretKeyFactory;
import com.workoutdone.rpgym.common.security.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {

    private final SecretKey secretKey; //JWT 서명에 사용할 비밀키
    private final long accessTokenExpirySeconds; //Access Token 만료시간(30분)

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-seconds}") long accessTokenExpirySeconds
    ) {
        //[SecretKey 생성 로직 공통화]
        this.secretKey = JwtSecretKeyFactory.create(secret);
        this.accessTokenExpirySeconds = accessTokenExpirySeconds;
    }

    // Access Token: sub=userId, role, iat, exp, jti. HS256으로 서명
    // jti는 로그아웃 시 Blacklist에서 어떤 토큰을 무효화할지 식별하는 토큰별 고유 값
    public String createAccessToken(UUID userId, UserRole role) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirySeconds);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                //[JWT Claim 이름 공통화]
                .claim(JwtClaimConstants.ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    // 서명을 검증한 뒤 Blacklist 등록에 필요한 jti/exp를 꺼낸다.
    // 서명이 올바르지 않거나 이미 만료된 토큰이면 JwtException이 발생한다.
    public AccessTokenClaims parseAccessToken(String accessToken) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(accessToken)
                .getPayload();

        return new AccessTokenClaims(claims.getId(), claims.getExpiration().toInstant());
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpirySeconds;
    }
}
