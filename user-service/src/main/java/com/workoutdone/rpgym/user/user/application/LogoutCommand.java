package com.workoutdone.rpgym.user.user.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogoutCommand {

    private UUID userId;
    private String refreshToken;

    // 이 요청을 인증하는 데 사용한 Access Token(Authorization 헤더). Blacklist에 등록할 대상
    private String accessToken;
}
