package com.workoutdone.rpgym.notification.questoffer.adapter.in.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Slack이 보낸 요청이 진짜인지 확인한다.
 * 이 엔드포인트는 게이트웨이에서 인증(JWT)을 걷어낸 public 엔드포인트라,
 * 이 검증이 없으면 누구든 URL만 알면 남의 Quest를 수락/거절시킬 수 있다.
 *
 * https://api.slack.com/authentication/verifying-requests-from-slack 의 v0 서명 방식을 그대로 구현한다.
 * Signing Secret은 Bot Token과 별개로 Slack App 설정(Basic Information)에서 발급받는 값이다.
 */
@Component
public class SlackSignatureVerifier {

    private static final String VERSION = "v0";
    private static final long MAX_TIMESTAMP_SKEW_SECONDS = 60 * 5; //5분

    private final String signingSecret;

    public SlackSignatureVerifier(@Value("${rpgym.slack.signing-secret}") String signingSecret) {
        this.signingSecret = signingSecret;
    }

    // 이 요청이 진짜 Slack이 보낸 게 맞는지 최종 판정
    public boolean isValid(String signature, String timestamp, byte[] rawBodyBytes) {
        if (rawBodyBytes == null || signature == null || timestamp == null || isTimestampTooOld(timestamp)) {
            return false;
        }

        // base string("v0:{timestamp}:{body}")을 문자열로 조립하지 않고 바이트로 직접 이어붙인다.
        // body를 String으로 바꿨다가 다시 바이트로 되돌리는 왕복 과정 자체를 없애서,
        // 그 사이의 문자셋 디코딩/인코딩으로 원본 바이트가 달라질 여지를 원천 차단한다.
        byte[] prefix = (VERSION + ":" + timestamp + ":").getBytes(StandardCharsets.UTF_8);
        byte[] baseString = new byte[prefix.length + rawBodyBytes.length];
        System.arraycopy(prefix, 0, baseString, 0, prefix.length);
        System.arraycopy(rawBodyBytes, 0, baseString, prefix.length, rawBodyBytes.length);

        String expected = VERSION + "=" + hmacSha256Hex(baseString);

        // 단순 equals()가 아니라 isEqual()을 사용
        // -- 문자열 비교 시간 차이로 서명을 한 글자씩 알아내는 타이밍 공격을 막기 위함이다.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    // 재생 공격(누군가 예전에 한 번 성공했던 진짜 요청을 몰래 저장해뒀다가 나중에 그대로 재전송) 방지.
    // 지금 시각이랑 요청에 적힌 시각이 5분 넘게 차이 나면 아무리 도장이 맞아도 거부함
    // Slack 공식 권장값인 5분을 쓴다.
    private boolean isTimestampTooOld(String timestamp) {
        try {
            long requestEpochSeconds = Long.parseLong(timestamp);
            long skew = Math.abs(Instant.now().getEpochSecond() - requestEpochSeconds);
            return skew > MAX_TIMESTAMP_SKEW_SECONDS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    // 바이트 배열을 Signing Secret으로 HMAC-SHA256 암호화해서 16진수 문자열로 돌려준다.
    private String hmacSha256Hex(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 서명 계산에 실패했다", e);
        }
    }
}
