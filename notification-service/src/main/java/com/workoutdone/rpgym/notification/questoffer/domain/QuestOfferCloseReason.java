package com.workoutdone.rpgym.notification.questoffer.domain;

/**
 * status가 CLOSED일 때 game-service가 처리를 거절한 이유. quest_offers.close_reason에 저장된다.
 * 처리 방식(재시도 안 함, 버튼 없음)은 모두 같고 카드 문구만 달라서 상태를 나누지 않고 이유만 따로 둔다.
 */
public enum QuestOfferCloseReason {
    ALREADY_DECIDED,
    EXPIRED,
    SUPERSEDED,
    NOT_FOUND,
    DATE_MISMATCH,
    UNKNOWN
}
