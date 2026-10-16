package com.workoutdone.rpgym.notification.questoffer.application;

import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

/**
 * Slack 버튼 클릭 접수 결과.
 * 접수됐는지(accepted)와 현재 행(offer)을 담음.
 *
 * @param accepted true면 이번 클릭이 접수됐다(PENDING/SENT -> PROCESSING).
 *                 false면 이미 접수/처리된 제안의 중복 클릭이라 아무것도 바뀌지 않았다.
 * @param offer    접수 후(또는 중복 클릭 시점의) 행. 중복 클릭이면 눌린 카드를 이 상태에 맞춰 바꾸는 데 쓴다.
 */
public record DecisionRequestResult(
        boolean accepted,
        QuestOffer offer
) {
}
