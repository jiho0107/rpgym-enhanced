package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.questoffer.adapter.in.kafka.dto.QuestSuggestedData;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Quest 제안 카드를 처음 발송만 한다. 카드 조립은 QuestOfferCards가 정한다.
 *
 * channel에는 DM 채널 ID가 아니라 사용자의 slackId를 그대로 넣는다 -- Slack이 DM을 자동으로 연다.
 * 보상(XP) 정보는 이 이벤트에 없어서 카드에 표시하지 않는다 (수락 이후에 정해지는 값).
 */
@Component
@RequiredArgsConstructor
public class QuestOfferSlackNotifier {

    private final SlackApiClient slackApiClient;
    private final QuestOfferCards questOfferCards;

    public SlackMessageResult sendOffer(String slackId, QuestSuggestedData data) {
        QuestOfferCard card = questOfferCards.offer(data.title(), data.suggestionId());
        return slackApiClient.postMessage(slackId, card.blocksJson(), card.fallbackText());
    }
}
