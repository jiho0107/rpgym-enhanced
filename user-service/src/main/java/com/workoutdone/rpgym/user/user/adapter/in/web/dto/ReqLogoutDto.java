package com.workoutdone.rpgym.user.user.adapter.in.web.dto;

import com.workoutdone.rpgym.user.user.application.LogoutCommand;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReqLogoutDto {

    @NotBlank(message = "리프레시 토큰을 입력해주세요.")
    @org.hibernate.validator.constraints.UUID(message = "리프레시 토큰 형식이 올바르지 않습니다.")
    private String refreshToken;

    public LogoutCommand toCommand(UUID userId, String accessToken) {
        return LogoutCommand.builder()
                .userId(userId)
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .build();
    }
}
