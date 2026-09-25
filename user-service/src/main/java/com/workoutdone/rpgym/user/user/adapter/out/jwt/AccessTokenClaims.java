package com.workoutdone.rpgym.user.user.adapter.out.jwt;

import java.time.Instant;

// Access Token에서 Blacklist 등록에 필요한 값만 꺼낸 결과
// jti는 이 기능 도입 이전에 발급된 토큰에는 없을 수 있어 null일 수 있다
public record AccessTokenClaims(String jti, Instant expiresAt) {
}
