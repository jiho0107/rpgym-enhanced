package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferSlackActions;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Quest 제안 카드를 상태별로 조립한다.
 * JSON을 만들기만 하고 발송이나 수정은 하지 않는다.
 *
 * 모든 상태의 카드는 같은 뼈대를 쓴다 -- 어떤 상태든 "같은 카드가 바뀐 것"처럼 보이게 하기 위해서다.
 *   ① header  : 항상 같은 문구
 *   ② section : 퀘스트 제목 (항상 그대로)
 *   ③ context : 상태 문구 (상태마다 이 한 줄만 바뀐다)
 *   ④ actions : 수락/거절 버튼 (처음 발송, 재시도 소진일 때만 있다)
 *
 * Slack 버튼에는 disabled 속성이 없어서, "버튼 비활성화"는 ④가 빠진 블록으로 메시지를 통째로 바꾸는 것으로 한다.
 */
@Component
@RequiredArgsConstructor
public class QuestOfferCards {

    static final String HEADER_TEXT = "⚔️ 오늘의 퀘스트가 도착했어요";
    static final String GUIDE_TEXT = "수락하면 오늘의 퀘스트로 등록돼요.";

    private final ObjectMapper objectMapper;

    /** ① 처음 발송. 푸시 알림 미리보기도 헤더와 같은 문구로 보이게 한다. */
    public QuestOfferCard offer(String title, UUID suggestionId) {
        return new QuestOfferCard(blocksJson(title, GUIDE_TEXT, suggestionId), HEADER_TEXT);
    }

    /** ② 클릭 직후 ~ 재시도 중. 버튼을 없애 같은 카드를 다시 누르지 못하게 한다. */
    public QuestOfferCard processing(String title, QuestOfferAction action) {
        String status = "⏳ " + actionLabel(action) + " 요청을 처리 중이에요...";
        return new QuestOfferCard(blocksJson(title, status, null), status);
    }

    /** ③ 사용자가 요청한 수락/거절이 반영됐다. */
    public QuestOfferCard completed(String title, QuestOfferAction action) {
        String status = action == QuestOfferAction.ACCEPT
                ? "✅ 퀘스트를 수락했어요"
                : "🙅 퀘스트를 거절했어요";
        return new QuestOfferCard(blocksJson(title, status, null), status);
    }

    /** ④ game-service가 처리를 확정적으로 거절했다. 이유마다 문구만 다르다. */
    public QuestOfferCard closed(String title, QuestOfferCloseReason reason) {
        String status = switch (reason) {
            case ALREADY_DECIDED -> "ℹ️ 이미 처리된 제안이에요";
            case EXPIRED -> "⌛ 만료된 제안이라 처리할 수 없어요";
            case SUPERSEDED -> "🔄 새로운 제안으로 대체됐어요";
            case NOT_FOUND, DATE_MISMATCH, UNKNOWN -> "ℹ️ 처리할 수 없는 제안이에요";
        };
        return new QuestOfferCard(blocksJson(title, status, null), status);
    }

    /** ⑤ 재시도를 다 썼다. 버튼을 다시 살려 사용자가 직접 누를 수 있게 한다. */
    public QuestOfferCard failed(String title, UUID suggestionId) {
        String status = "⚠️ 일시적인 오류로 처리하지 못했어요. 다시 눌러주세요.";
        return new QuestOfferCard(blocksJson(title, status, suggestionId), status);
    }

    /**
     * ⑥-a 같은 제안 카드가 중복 발송되어 두 장일 때, 다른 카드의 클릭이 아직 처리 중인데 이 카드를 누른 경우.
     * "처리 중"이라고 쓰면 처리가 끝나도 이 카드는 그대로 남아(처리기는 첫 클릭의 카드만 바꾼다) 틀린 말이 된다.
     * 시간이 지나도 맞는 문구를 쓴다. (처리가 끝난 뒤 누른 ⑥-b는 completed/closed를 그대로 쓴다)
     */
    public QuestOfferCard handledElsewhere(String title) {
        String status = "ℹ️ 이 제안은 다른 메시지에서 처리 중이에요. 결과는 먼저 누른 메시지에서 확인해주세요.";
        return new QuestOfferCard(blocksJson(title, status, null), status);
    }

    /**
     * ⑥ 이미 접수됐거나 처리가 끝난 제안의 카드가 또 눌렸을 때(중복 클릭), 그 눌린 카드에 그릴 내용을 고른다.
     * 같은 제안 카드가 중복 발송되어 두 장일 때 생기는 상황이다. 발송/수정은 하지 않고 카드만 골라 돌려준다.
     *
     * 이번에 눌린 버튼이 아니라 DB에 기록된 실제 결과(첫 클릭의 행동, 종료 사유)를 기준으로 고른다 --
     * 카드 1에서 수락, 카드 2에서 거절을 눌렀어도 실제로 처리되는 건 수락이다.
     *
     *   PROCESSING              -> ⑥-a handledElsewhere (아직 처리 중)
     *   ACCEPTED / REJECTED     -> ⑥-b completed (③과 같은 화면)
     *   CLOSED                  -> ⑥-b closed (④와 같은 화면)
     *   PENDING / SENT          -> 중복 클릭이 아니다(정상 접수됐어야 함). 잘못 부른 것이라 예외
     */
    public QuestOfferCard forDuplicateClick(QuestOffer offer) {
        return switch (offer.getStatus()) {
            case PROCESSING -> handledElsewhere(offer.getTitle());
            case ACCEPTED, REJECTED -> completed(offer.getTitle(), offer.getRequestedAction());
            case CLOSED -> closed(offer.getTitle(), offer.getCloseReason());
            case PENDING, SENT -> throw new IllegalStateException(
                    "중복 클릭이 아닌 상태다. suggestionId=" + offer.getSuggestionId() + " status=" + offer.getStatus());
        };
    }

    private static String actionLabel(QuestOfferAction action) {
        return action == QuestOfferAction.ACCEPT ? "수락" : "거절";
    }


    /**
     * @param suggestionIdForButtons null이면 버튼(④)을 넣지 않는다.
     *                               버튼 value에는 questId가 아니라 suggestionId를 담는다 -- 이 시점엔 questId가 없다.
     */
    private String blocksJson(String title, String contextText, UUID suggestionIdForButtons) {
        ArrayNode blocks = objectMapper.createArrayNode();

        // header 블록은 plain_text만 지원한다 (mrkdwn 불가)
        ObjectNode header = blocks.addObject();
        header.put("type", "header");
        header.putObject("text")
                .put("type", "plain_text")
                .put("text", HEADER_TEXT);

        ObjectNode section = blocks.addObject();
        section.put("type", "section");
        section.putObject("text")
                .put("type", "mrkdwn")
                .put("text", "*" + title + "*");

        ObjectNode context = blocks.addObject();
        context.put("type", "context");
        context.putArray("elements").addObject()
                .put("type", "mrkdwn")
                .put("text", contextText);

        if (suggestionIdForButtons != null) {
            String suggestionId = suggestionIdForButtons.toString();
            ObjectNode actions = blocks.addObject();
            actions.put("type", "actions");
            ArrayNode elements = actions.putArray("elements");
            elements.add(buttonNode("수락", QuestOfferSlackActions.ACCEPT, suggestionId, "primary"));
            elements.add(buttonNode("거절", QuestOfferSlackActions.REJECT, suggestionId, "danger"));
        }

        return blocks.toString();
    }

    // 버튼 하나 생성
    private ObjectNode buttonNode(String label, String actionId, String value, String style) {
        ObjectNode button = objectMapper.createObjectNode();
        button.put("type", "button");
        button.putObject("text")
                .put("type", "plain_text")
                .put("text", label);
        button.put("style", style);
        button.put("action_id", actionId);
        button.put("value", value);
        return button;
    }
}
