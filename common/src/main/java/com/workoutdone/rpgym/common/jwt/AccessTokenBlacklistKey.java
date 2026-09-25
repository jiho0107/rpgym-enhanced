package com.workoutdone.rpgym.common.jwt;

// Access Token Blacklist의 Redis Key 규칙
// User Service(등록)와 Gateway(조회)가 같은 Key를 바라보도록 공통 모듈에서 관리
// "access-token-blacklist:{jti}" key로 저장
public final class AccessTokenBlacklistKey {

    private static final String PREFIX = "access-token-blacklist:";

    private AccessTokenBlacklistKey() {
    }

    public static String of(String jti) {
        return PREFIX + jti;
    }
}
