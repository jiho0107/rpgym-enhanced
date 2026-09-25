package com.workoutdone.rpgym.user.user.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.user.user.adapter.out.jwt.AccessTokenClaims;
import com.workoutdone.rpgym.user.user.adapter.out.jwt.JwtProvider;
import com.workoutdone.rpgym.user.user.adapter.out.redis.AccessTokenBlacklist;
import com.workoutdone.rpgym.user.user.adapter.out.redis.RefreshTokenStore;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    private static final String REFRESH_TOKEN = "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33";
    private static final String ACCESS_TOKEN = "header.payload.signature";
    private static final String JTI = "3b1f0c9e-5d2a-4e7b-8c41-9a6e2f0d1b57";

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private AccessTokenBlacklist accessTokenBlacklist;

    @InjectMocks
    private LogoutService logoutService;

    private LogoutCommand command(UUID userId) {
        return LogoutCommand.builder()
                .userId(userId)
                .refreshToken(REFRESH_TOKEN)
                .accessToken(ACCESS_TOKEN)
                .build();
    }

    private AccessTokenClaims claimsExpiringIn(long seconds) {
        return new AccessTokenClaims(JTI, Instant.now().plusSeconds(seconds));
    }

    @Test
    @DisplayName("본인 소유의 refreshToken이면 요청자 userId와 함께 Redis에서 삭제하고 Access Token을 Blacklist에 등록한다")
    void logout_success() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(claimsExpiringIn(1800));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
        verify(accessTokenBlacklist).add(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Blacklist에는 Access Token의 jti가 등록되고, TTL은 토큰의 남은 만료 시간과 같다")
    void logout_blacklistsJtiWithRemainingLifetime() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(claimsExpiringIn(1800));

        logoutService.logout(command(userId));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(accessTokenBlacklist).add(org.mockito.ArgumentMatchers.eq(JTI), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(1795), Duration.ofSeconds(1800));
    }

    @Test
    @DisplayName("존재하지 않거나 이미 폐기된 refreshToken이면 삭제 없이 성공 처리하지만, Access Token은 Blacklist에 등록한다")
    void logout_tokenNotFound_stillBlacklistsAccessToken() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.empty());
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(claimsExpiringIn(1800));

        logoutService.logout(command(userId));

        verify(refreshTokenStore, never()).delete(anyString(), any(UUID.class));
        verify(accessTokenBlacklist).add(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("다른 사용자 소유의 refreshToken이면 FORBIDDEN 예외를 던지고 삭제도, Access Token의 Blacklist 등록도 하지 않는다")
    void logout_otherUsersToken_throwsForbidden() {
        UUID ownerId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(ownerId));

        assertThatThrownBy(() -> logoutService.logout(command(requesterId)))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verify(refreshTokenStore, never()).delete(anyString(), any(UUID.class));
        verifyNoInteractions(jwtProvider, accessTokenBlacklist);
    }

    @Test
    @DisplayName("이미 만료된 Access Token이면 Blacklist에 등록하지 않는다")
    void logout_expiredAccessToken_isNotBlacklisted() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(claimsExpiringIn(-1));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
        verifyNoInteractions(accessTokenBlacklist);
    }

    @Test
    @DisplayName("jti가 없는 Access Token(Blacklist 도입 이전에 발급된 토큰)이면 Blacklist에 등록하지 않는다")
    void logout_accessTokenWithoutJti_isNotBlacklisted() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN))
                .willReturn(new AccessTokenClaims(null, Instant.now().plusSeconds(1800)));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
        verifyNoInteractions(accessTokenBlacklist);
    }

    @Test
    @DisplayName("Access Token 파싱에 실패해도 예외 없이 로그아웃(refreshToken 폐기)은 성공 처리한다")
    void logout_invalidAccessToken_stillSucceeds() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willThrow(new JwtException("invalid"));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
        verifyNoInteractions(accessTokenBlacklist);
    }

    @Test
    @DisplayName("Blacklist 등록 중 Redis 장애가 나도 예외 없이 로그아웃(refreshToken 폐기)은 성공 처리한다")
    void logout_blacklistFailure_stillSucceeds() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));
        given(jwtProvider.parseAccessToken(ACCESS_TOKEN)).willReturn(claimsExpiringIn(1800));
        willThrow(new RedisConnectionFailureException("redis down"))
                .given(accessTokenBlacklist).add(anyString(), any(Duration.class));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
    }
}
