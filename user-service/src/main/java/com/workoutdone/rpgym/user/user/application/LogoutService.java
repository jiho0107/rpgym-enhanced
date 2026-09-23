package com.workoutdone.rpgym.user.user.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.user.user.adapter.out.redis.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenStore refreshTokenStore;

    public void logout(LogoutCommand command) {
        Optional<UUID> ownerId = refreshTokenStore.findUserId(command.getRefreshToken());

        // 이미 폐기됐거나 존재한 적 없는 토큰이면 그대로 성공 처리한다.
        // 로그아웃은 여러 번 호출돼도 최종 상태가 같아야 하므로(멱등) 에러로 취급하지 않는다.
        if (ownerId.isEmpty()) {
            return;
        }

        // 본인 소유가 아닌 refreshToken은 폐기하지 않고 거부한다.
        if (!ownerId.get().equals(command.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN);
        }

        refreshTokenStore.delete(command.getRefreshToken(), command.getUserId());

        ////TO-DO: accessToken은 무상태(stateless) JWT라 여기서 폐기해도 만료 전까지는 계속 유효하다.
        ////       즉시 무효화가 필요해지면 accessToken을 Redis 블랙리스트로 관리하는 기능을 추가한다.
        ////       이땐 JwtProvider가 accessToken 발급 시 jti 클레임을 추가로 넣어야 하고,
        ////       게이트웨이가 그 jti를 X-Token-Jti 같은 헤더로 여기까지 전달해줘야 하며,
        ////       게이트웨이 쪽 JWT 검증 로직도 매 요청마다 그 블랙리스트를 조회하도록 바뀌어야 한다.
        ////       그 전까지는 accessToken의 짧은 만료 시간(expiresIn)에만 의존한다.
    }
}
