package com.workoutdone.rpgym.user.user.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.user.user.adapter.out.redis.RefreshTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    private static final String REFRESH_TOKEN = "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33";

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @InjectMocks
    private LogoutService logoutService;

    private LogoutCommand command(UUID userId) {
        return LogoutCommand.builder()
                .userId(userId)
                .refreshToken(REFRESH_TOKEN)
                .build();
    }

    @Test
    @DisplayName("본인 소유의 refreshToken이면 요청자 userId와 함께 Redis에서 삭제한다(역인덱스 정리에 userId 필요)")
    void logout_success() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(userId));

        logoutService.logout(command(userId));

        verify(refreshTokenStore).delete(REFRESH_TOKEN, userId);
    }

    @Test
    @DisplayName("존재하지 않거나 이미 폐기된 refreshToken이면 예외 없이 그대로 종료한다(멱등)")
    void logout_tokenNotFound_isIdempotent() {
        UUID userId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.empty());

        logoutService.logout(command(userId));

        verify(refreshTokenStore, never()).delete(anyString(), any(UUID.class));
    }

    @Test
    @DisplayName("다른 사용자 소유의 refreshToken이면 FORBIDDEN 예외를 던지고 삭제하지 않는다")
    void logout_otherUsersToken_throwsForbidden() {
        UUID ownerId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        given(refreshTokenStore.findUserId(REFRESH_TOKEN)).willReturn(Optional.of(ownerId));

        assertThatThrownBy(() -> logoutService.logout(command(requesterId)))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verify(refreshTokenStore, never()).delete(anyString(), any(UUID.class));
    }
}
