package com.workoutdone.rpgym.user.user.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.user.user.adapter.out.jwt.AccessTokenClaims;
import com.workoutdone.rpgym.user.user.adapter.out.jwt.JwtProvider;
import com.workoutdone.rpgym.user.user.adapter.out.redis.AccessTokenBlacklist;
import com.workoutdone.rpgym.user.user.adapter.out.redis.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenStore refreshTokenStore;
    private final JwtProvider jwtProvider;
    private final AccessTokenBlacklist accessTokenBlacklist;

    public void logout(LogoutCommand command) {
        Optional<UUID> ownerId = refreshTokenStore.findUserId(command.getRefreshToken());

        // 본인 소유가 아닌 refreshToken은 폐기하지 않고 거부한다. 이 경우 Access Token도 Blacklist에 등록하지 않는다.
        if (ownerId.isPresent() && !ownerId.get().equals(command.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN);
        }

        // 이미 폐기됐거나 존재한 적 없는 refresh 토큰이면 폐기할 것이 없으므로 그대로 성공 처리한다.
        // 로그아웃은 여러 번 호출돼도 최종 상태가 같아야 하므로(멱등) 에러로 취급하지 않는다.
        ownerId.ifPresent(userId -> refreshTokenStore.delete(command.getRefreshToken(), userId));

        // refreshToken이 이미 없는 경우에도 이 요청에 쓰인 Access Token은 무효화한다.
        blacklistAccessToken(command.getAccessToken());
    }

    // Access Token은 무상태(stateless) JWT라 refreshToken을 폐기해도 만료 전까지는 계속 유효하다.
    // 그래서 이 요청에 쓰인 Access Token의 jti를 Redis Blacklist에 등록하고, Gateway가 매 요청마다 이를 조회해 차단한다.
    // Blacklist는 짧은 만료 시간(expiresIn) 위에 얹는 추가 방어선이라, 등록에 실패해도 로그아웃 자체는 성공 처리한다.
    private void blacklistAccessToken(String accessToken) {
        try {
            AccessTokenClaims claims = jwtProvider.parseAccessToken(accessToken);

            // jti가 없는 토큰(이 기능 도입 이전에 발급된 토큰)은 식별할 수 없어 등록하지 않는다
            if (claims.jti() == null) {
                return;
            }

            // Blacklist Key는 토큰의 남은 만료 시간까지만 유지한다. 이미 만료됐다면 등록할 필요가 없다.
            long ttlSeconds = claims.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            if (ttlSeconds > 0) {
                accessTokenBlacklist.add(claims.jti(), Duration.ofSeconds(ttlSeconds));
            }
        } catch (RuntimeException e) {
            log.warn("Access Token Blacklist 등록에 실패했습니다. 로그아웃은 그대로 성공 처리합니다: {}", e.toString());
        }
    }
}
