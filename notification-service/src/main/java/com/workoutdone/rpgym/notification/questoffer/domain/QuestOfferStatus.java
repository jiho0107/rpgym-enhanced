package com.workoutdone.rpgym.notification.questoffer.domain;

/**
 * PENDING    -- 발송을 시도했지만 Slack 성공이 아직 기록되지 않음
 * SENT       -- Slack 발송 완료, 사용자의 클릭을 기다림
 * PROCESSING -- 클릭을 접수했고 game-service에 반영하는 중 (재시도 포함)
 * ACCEPTED / REJECTED -- 사용자가 요청한 수락/거절이 game-service에 반영됨
 * CLOSED     -- game-service가 처리를 확정적으로 거절함 (만료, 대체 등). 이유는 close_reason에 남긴다
 */
public enum QuestOfferStatus {
    PENDING,
    SENT,
    PROCESSING,
    ACCEPTED,
    REJECTED,
    CLOSED
}
