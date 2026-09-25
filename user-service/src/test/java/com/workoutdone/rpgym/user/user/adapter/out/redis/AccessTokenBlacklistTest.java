package com.workoutdone.rpgym.user.user.adapter.out.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AccessTokenBlacklist가 Gateway가 조회하는 것과 같은 Key/TTL 규칙으로 Redis에 저장하는지 검증한다.
 * TTL 만료 같은 실제 Redis 동작은 Mock으로 증명할 수 없어 실제 Redis를 띄운다.
 *
 * Docker가 없는 환경에서는 실패가 아니라 건너뛴다 — disabledWithoutDocker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccessTokenBlacklistTest {

    // Gateway(AccessTokenBlacklistKey)와 같은 Key 규칙. 한쪽만 바뀌면 차단이 조용히 무력화되므로 리터럴로 고정해서 검증한다
    private static final String KEY_PREFIX = "access-token-blacklist:";

    // docker-compose와 같은 이미지 버전을 쓴다
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.2")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private AccessTokenBlacklist accessTokenBlacklist;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        accessTokenBlacklist = new AccessTokenBlacklist(redisTemplate);
    }

    @Test
    @DisplayName("jti를 Key로 하는 항목이 등록된다")
    void add_storesEntryKeyedByJti() {
        String jti = UUID.randomUUID().toString();

        accessTokenBlacklist.add(jti, Duration.ofMinutes(30));

        assertThat(redisTemplate.hasKey(KEY_PREFIX + jti)).isTrue();
    }

    @Test
    @DisplayName("다른 jti는 등록되지 않는다(토큰 하나만 무효화된다)")
    void add_doesNotAffectOtherTokens() {
        String revoked = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();

        accessTokenBlacklist.add(revoked, Duration.ofMinutes(30));

        assertThat(redisTemplate.hasKey(KEY_PREFIX + other)).isFalse();
    }

    @Test
    @DisplayName("지정한 TTL이 설정된다")
    void add_setsGivenTtl() {
        String jti = UUID.randomUUID().toString();

        accessTokenBlacklist.add(jti, Duration.ofSeconds(1800));

        Long ttl = redisTemplate.getExpire(KEY_PREFIX + jti);
        assertThat(ttl).isBetween(1790L, 1800L);
    }

    @Test
    @DisplayName("TTL이 지나면 Redis에서 자동으로 삭제된다(자연 만료된 토큰이 Blacklist에 계속 쌓이지 않는다)")
    void add_entryExpiresAfterTtl() {
        String jti = UUID.randomUUID().toString();

        accessTokenBlacklist.add(jti, Duration.ofSeconds(1));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redisTemplate.hasKey(KEY_PREFIX + jti)).isFalse());
    }
}
