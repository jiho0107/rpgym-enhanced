package com.workoutdone.rpgym.notification.questoffer.domain;

import com.workoutdone.rpgym.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    QUEST_OFFER_NOT_FOUND(
            "QUEST_OFFER_NOT_FOUND",
            HttpStatus.NOT_FOUND,
            "quest_offer를 찾을 수 없습니다."
    ),

    INVALID_SLACK_SIGNATURE(
            "INVALID_SLACK_SIGNATURE",
            HttpStatus.UNAUTHORIZED,
            "Slack 서명 검증에 실패했습니다."
    ),

    GAME_SERVICE_CALL_FAILED(
            "GAME_SERVICE_CALL_FAILED",
            HttpStatus.BAD_GATEWAY,
            "game-service 호출에 실패했습니다."
    );

    private final String code;
    private final HttpStatus status;
    private final String message;
}
