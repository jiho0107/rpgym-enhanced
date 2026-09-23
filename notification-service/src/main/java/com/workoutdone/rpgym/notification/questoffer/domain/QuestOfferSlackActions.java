package com.workoutdone.rpgym.notification.questoffer.domain;

/**
 * Quest 제안 Slack 카드의 버튼 action_id.
 * 카드를 만드는 쪽(QuestOfferSlackNotifier)과 클릭을 해석하는 쪽(SlackInteractionController)이
 * 같은 문자열을 따로 들고 있으면 오타로 어긋날 수 있어 한 곳에 모아둔다.
 */
public final class QuestOfferSlackActions {

    public static final String ACCEPT = "quest_offer_accept";
    public static final String REJECT = "quest_offer_reject";

    private QuestOfferSlackActions() {
    }
}
