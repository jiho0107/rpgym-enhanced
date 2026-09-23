package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSignatureVerifierTest {

    private static final String SIGNING_SECRET = "test-signing-secret";
    private static final String RAW_BODY = "payload=%7B%22test%22%3Atrue%7D";

    private SlackSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new SlackSignatureVerifier(SIGNING_SECRET);
    }

    @Test
    @DisplayName("올바른 서명이면 통과한다")
    void validSignaturePasses() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, RAW_BODY);

        assertThat(verifier.isValid(signature, timestamp, RAW_BODY)).isTrue();
    }

    @Test
    @DisplayName("서명이 틀리면 거부한다")
    void invalidSignatureFails() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        assertThat(verifier.isValid("v0=deadbeef", timestamp, RAW_BODY)).isFalse();
    }

    @Test
    @DisplayName("본문이 서명 계산 때와 한 글자라도 다르면(위·변조) 거부한다")
    void tamperedBodyFails() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, RAW_BODY);

        assertThat(verifier.isValid(signature, timestamp, "payload=%7B%22test%22%3Afalse%7D")).isFalse();
    }

    @Test
    @DisplayName("타임스탬프가 5분 넘게 지났으면 서명이 맞아도 거부한다 (재생 공격 방지)")
    void tooOldTimestampFails() {
        String oldTimestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        String signature = sign(oldTimestamp, RAW_BODY);

        assertThat(verifier.isValid(signature, oldTimestamp, RAW_BODY)).isFalse();
    }

    @Test
    @DisplayName("signature나 timestamp가 없으면 거부한다")
    void missingHeaderFails() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        assertThat(verifier.isValid(null, timestamp, RAW_BODY)).isFalse();
        assertThat(verifier.isValid("v0=abc", null, RAW_BODY)).isFalse();
    }

    // Slack과 동일한 v0 서명 공식(HMAC-SHA256)으로 테스트용 정답 서명을 만든다.
    private String sign(String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(("v0:" + timestamp + ":" + rawBody).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
