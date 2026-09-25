package com.workoutdone.rpgym.user.user.adapter.out.redis;

import com.workoutdone.rpgym.common.jwt.AccessTokenBlacklistKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
//로그아웃된 Access Token을 Redis에 저장 (조회는 Gateway가 담당)
//Redis 어댑터
public class AccessTokenBlacklist {

    // 값 자체는 의미 없고 Key의 존재 여부만 확인한다
    private static final String VALUE = "revoked";

    private final StringRedisTemplate redisTemplate;

    // TTL은 해당 Access Token의 남은 만료 시간으로 지정해서, 자연 만료된 뒤에는 Redis에서 자동으로 삭제되게 한다
    public void add(String jti, Duration ttl) {
        redisTemplate.opsForValue().set(AccessTokenBlacklistKey.of(jti), VALUE, ttl);
    }
}
