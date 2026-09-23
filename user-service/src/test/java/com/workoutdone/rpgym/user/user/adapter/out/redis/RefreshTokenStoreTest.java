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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefreshTokenStore의 Lua 스크립트가 refresh-token:{token} 키와 역인덱스 refresh-token-owner:{userId}를
 * 함께 정리하는지 검증한다. Mock으로는 Redis 안에서의 실제 동작(SREM, 빈 Set 자동 삭제)을 증명할 수 없어 실제 Redis를 띄운다.
 *
 * Docker가 없는 환경에서는 실패가 아니라 건너뛴다 — disabledWithoutDocker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenStoreTest {

    private static final String KEY_PREFIX = "refresh-token:";
    private static final String OWNER_KEY_PREFIX = "refresh-token-owner:";

    // docker-compose와 같은 이미지 버전을 쓴다
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.2")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RefreshTokenStore refreshTokenStore;

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
        refreshTokenStore = new RefreshTokenStore(redisTemplate);
    }

    @Test
    @DisplayName("로그인 시 refresh-token 키와 역인덱스에 토큰이 저장된다")
    void save_storesTokenAndOwnerIndex() {
        UUID userId = UUID.randomUUID();

        refreshTokenStore.save("token-a", userId);

        assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "token-a")).isEqualTo(userId.toString());
        assertThat(redisTemplate.opsForSet().members(OWNER_KEY_PREFIX + userId)).containsExactly("token-a");
    }

    @Test
    @DisplayName("로그아웃 시 refresh-token 키가 삭제되고, 역인덱스에서도 토큰이 제거되어 빈 Set이 된 역인덱스 키는 사라진다")
    void delete_lastToken_removesTokenAndOwnerKey() {
        UUID userId = UUID.randomUUID();
        refreshTokenStore.save("token-a", userId);

        refreshTokenStore.delete("token-a", userId);

        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-a")).isFalse();
        assertThat(redisTemplate.hasKey(OWNER_KEY_PREFIX + userId)).isFalse();
    }

    @Test
    @DisplayName("다중 기기 로그인 상태에서 로그아웃하면 요청한 토큰만 제거되고 다른 기기의 토큰은 유지된다")
    void delete_oneOfMultipleTokens_keepsOtherTokens() {
        UUID userId = UUID.randomUUID();
        refreshTokenStore.save("token-phone", userId);
        refreshTokenStore.save("token-pc", userId);

        refreshTokenStore.delete("token-phone", userId);

        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-phone")).isFalse();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-pc")).isTrue();
        assertThat(redisTemplate.opsForSet().members(OWNER_KEY_PREFIX + userId)).containsExactly("token-pc");
    }

    @Test
    @DisplayName("이미 폐기된 토큰으로 다시 로그아웃해도 예외 없이 끝나고 다른 토큰에 영향이 없다(멱등)")
    void delete_alreadyDeletedToken_isIdempotent() {
        UUID userId = UUID.randomUUID();
        refreshTokenStore.save("token-a", userId);
        refreshTokenStore.save("token-b", userId);
        refreshTokenStore.delete("token-a", userId);

        refreshTokenStore.delete("token-a", userId);

        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-b")).isTrue();
        assertThat(redisTemplate.opsForSet().members(OWNER_KEY_PREFIX + userId)).containsExactly("token-b");
    }

    @Test
    @DisplayName("로그아웃은 다른 사용자의 토큰과 역인덱스에 영향을 주지 않는다")
    void delete_doesNotAffectOtherUsers() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        refreshTokenStore.save("token-a", userA);
        refreshTokenStore.save("token-b", userB);

        refreshTokenStore.delete("token-a", userA);

        assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "token-b")).isEqualTo(userB.toString());
        assertThat(redisTemplate.opsForSet().members(OWNER_KEY_PREFIX + userB)).containsExactly("token-b");
    }

    @Test
    @DisplayName("일부 기기에서 로그아웃한 뒤 회원 탈퇴하면 남아 있던 토큰과 역인덱스가 모두 삭제된다")
    void deleteAllByUserId_afterPartialLogout_removesRemainingTokens() {
        UUID userId = UUID.randomUUID();
        refreshTokenStore.save("token-phone", userId);
        refreshTokenStore.save("token-pc", userId);
        refreshTokenStore.delete("token-phone", userId);

        refreshTokenStore.deleteAllByUserId(userId);

        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-pc")).isFalse();
        assertThat(redisTemplate.hasKey(OWNER_KEY_PREFIX + userId)).isFalse();
    }

    @Test
    @DisplayName("토큰 회전 시 기존 토큰은 역인덱스에서 빠지고 새 토큰이 추가된다")
    void rotate_replacesTokenInOwnerIndex() {
        UUID userId = UUID.randomUUID();
        refreshTokenStore.save("token-old", userId);

        boolean rotated = refreshTokenStore.rotate("token-old", "token-new", userId);

        assertThat(rotated).isTrue();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + "token-old")).isFalse();
        assertThat(redisTemplate.opsForValue().get(KEY_PREFIX + "token-new")).isEqualTo(userId.toString());
        assertThat(redisTemplate.opsForSet().members(OWNER_KEY_PREFIX + userId)).containsExactly("token-new");
    }
}
