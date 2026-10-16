package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.config.AsyncConfig;
import com.workoutdone.rpgym.notification.slack.SlackApiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 이미 보낸 Quest 제안 카드를 새 카드로 통째로 바꾼다.
 * 카드 내용은 QuestOfferCards가 정하고, 여기서는
 *  - 어느 Slack 메시지를 바꿀지와
 *  - 어떤 수단으로 바꿀지(chat.update -> 실패 시 response_url)만 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestOfferCardUpdater {

    private final SlackApiClient slackApiClient;

    /**
     * ⑥ 중복 클릭이 들어온 카드를 바꾼다. 클릭 요청 스레드를 붙잡지 않도록 비동기로 실행한다.
     *
     * 이번 클릭의 payload 값만 쓴다. DB의 slack_message_ts는 이 카드가 아닐 수 있고,
     * DB의 clicked_*는 첫 클릭 카드의 위치라 쓰면 엉뚱한 카드가 바뀔 수 있다.
     *   1순위: chat.update(이번 클릭의 channel_id, message_ts)
     *   2순위: 이번 클릭의 response_url
     * 둘 다 실패해도 예외를 던지지 않는다 -- 표시만 맞추는 작업이고, 카드에 버튼이 남아 있으니 다시 누르면 다시 시도된다.
     */
    @Async(AsyncConfig.SLACK_CARD_UPDATE_EXECUTOR)
    public void updateDuplicateClickCard(String clickedChannelId, String clickedMessageTs, String responseUrl,
                                         QuestOfferCard card) {
        if (clickedChannelId != null && clickedMessageTs != null) {
            try {
                slackApiClient.updateMessage(clickedChannelId, clickedMessageTs, card.blocksJson(), card.fallbackText());
                return;
            } catch (RuntimeException e) {
                log.warn("중복 클릭 카드 chat.update 실패, response_url로 넘어간다. channel={} ts={}",
                        clickedChannelId, clickedMessageTs, e);
            }
        }

        if (responseUrl != null) {
            try {
                slackApiClient.respond(responseUrl, card.blocksJson(), card.fallbackText());
                return;
            } catch (RuntimeException e) {
                log.warn("중복 클릭 카드 response_url 갱신 실패. channel={} ts={}", clickedChannelId, clickedMessageTs, e);
            }
        }

        log.warn("중복 클릭 카드를 갱신하지 못했다. channel={} ts={}", clickedChannelId, clickedMessageTs);
    }
}
