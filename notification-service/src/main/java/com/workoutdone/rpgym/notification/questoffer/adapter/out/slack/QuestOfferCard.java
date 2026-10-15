package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

/**
 * Slack에 보낼(또는 바꿀) Quest 제안 카드 한 장.
 *
 * @param blocksJson   Block Kit blocks 배열 JSON
 * @param fallbackText 블록을 그릴 수 없는 환경(푸시 알림 미리보기 등)에서 대신 보이는 문구
 */
public record QuestOfferCard(
        String blocksJson,
        String fallbackText
) {
}
