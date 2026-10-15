package com.workoutdone.rpgym.notification.questoffer.adapter.out.slack;

import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestOfferCardsTest {

    private static final String TITLE = "20분 산책하기";
    private static final UUID SUGGESTION_ID = UUID.fromString("7c2e5a91-6f0b-4c88-b3d2-15ae9047cc61");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestOfferCards cards = new QuestOfferCards(objectMapper);

    private JsonNode blocks(QuestOfferCard card) throws Exception {
        return objectMapper.readTree(card.blocksJson());
    }

    private static String contextText(JsonNode blocks) {
        return blocks.get(2).get("elements").get(0).get("text").asText();
    }

    /** 모든 상태 카드가 공유하는 뼈대: header(같은 문구) -> 제목 section -> 상태 context */
    private static void assertCommonSkeleton(JsonNode blocks) {
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
        assertThat(blocks.get(0).get("text").get("type").asText()).isEqualTo("plain_text");
        assertThat(blocks.get(0).get("text").get("text").asText()).isEqualTo("⚔️ 오늘의 퀘스트가 도착했어요");

        assertThat(blocks.get(1).get("type").asText()).isEqualTo("section");
        assertThat(blocks.get(1).get("text").get("text").asText()).isEqualTo("*" + TITLE + "*");

        assertThat(blocks.get(2).get("type").asText()).isEqualTo("context");
    }

    private static void assertHasButtons(JsonNode blocks) {
        assertThat(blocks).hasSize(4);
        JsonNode elements = blocks.get(3).get("elements");
        assertThat(blocks.get(3).get("type").asText()).isEqualTo("actions");
        assertThat(elements.get(0).get("action_id").asText()).isEqualTo("quest_offer_accept");
        assertThat(elements.get(0).get("value").asText()).isEqualTo(SUGGESTION_ID.toString());
        assertThat(elements.get(1).get("action_id").asText()).isEqualTo("quest_offer_reject");
        assertThat(elements.get(1).get("value").asText()).isEqualTo(SUGGESTION_ID.toString());
    }

    private static void assertNoButtons(JsonNode blocks) {
        assertThat(blocks).hasSize(3);
        for (JsonNode block : blocks) {
            assertThat(block.get("type").asText()).isNotEqualTo("actions");
        }
    }

    @Test
    @DisplayName("① offer — 헤더, 제목, 안내 문구, 수락/거절 버튼. 미리보기 문구는 헤더와 같다")
    void offerCard() throws Exception {
        QuestOfferCard card = cards.offer(TITLE, SUGGESTION_ID);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks)).isEqualTo("수락하면 오늘의 퀘스트로 등록돼요.");
        assertHasButtons(blocks);
        assertThat(card.fallbackText()).isEqualTo("⚔️ 오늘의 퀘스트가 도착했어요");
    }

    @ParameterizedTest
    @CsvSource({
            "ACCEPT, ⏳ 수락 요청을 처리 중이에요...",
            "REJECT, ⏳ 거절 요청을 처리 중이에요..."
    })
    @DisplayName("② processing — 누른 버튼에 맞는 처리 중 문구, 버튼 없음")
    void processingCard(QuestOfferAction action, String expected) throws Exception {
        QuestOfferCard card = cards.processing(TITLE, action);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks)).isEqualTo(expected);
        assertNoButtons(blocks);
        assertThat(card.fallbackText()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "ACCEPT, ✅ 퀘스트를 수락했어요",
            "REJECT, 🙅 퀘스트를 거절했어요"
    })
    @DisplayName("③ completed — 수락/거절 완료 문구, 버튼 없음")
    void completedCard(QuestOfferAction action, String expected) throws Exception {
        QuestOfferCard card = cards.completed(TITLE, action);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks)).isEqualTo(expected);
        assertNoButtons(blocks);
    }

    @ParameterizedTest
    @CsvSource({
            "ALREADY_DECIDED, ℹ️ 이미 처리된 제안이에요",
            "EXPIRED, ⌛ 만료된 제안이라 처리할 수 없어요",
            "SUPERSEDED, 🔄 새로운 제안으로 대체됐어요",
            "NOT_FOUND, ℹ️ 처리할 수 없는 제안이에요",
            "DATE_MISMATCH, ℹ️ 처리할 수 없는 제안이에요",
            "UNKNOWN, ℹ️ 처리할 수 없는 제안이에요"
    })
    @DisplayName("④ closed — 종료 사유마다 정해진 문구, 버튼 없음")
    void closedCard(QuestOfferCloseReason reason, String expected) throws Exception {
        QuestOfferCard card = cards.closed(TITLE, reason);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks)).isEqualTo(expected);
        assertNoButtons(blocks);
    }

    @ParameterizedTest
    @EnumSource(QuestOfferCloseReason.class)
    @DisplayName("④ closed — 모든 종료 사유에 문구가 있다 (새 사유가 추가돼도 빈 카드가 나가지 않는다)")
    void everyCloseReasonHasText(QuestOfferCloseReason reason) throws Exception {
        assertThat(contextText(blocks(cards.closed(TITLE, reason)))).isNotBlank();
    }

    @Test
    @DisplayName("⑤ failed — 재시도 소진 안내 문구와 함께 수락/거절 버튼을 다시 넣는다")
    void failedCard() throws Exception {
        QuestOfferCard card = cards.failed(TITLE, SUGGESTION_ID);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks)).isEqualTo("⚠️ 일시적인 오류로 처리하지 못했어요. 다시 눌러주세요.");
        assertHasButtons(blocks);
    }

    @Test
    @DisplayName("⑥-a handledElsewhere — 시간이 지나도 틀린 말이 되지 않는 문구, 버튼 없음")
    void handledElsewhereCard() throws Exception {
        QuestOfferCard card = cards.handledElsewhere(TITLE);
        JsonNode blocks = blocks(card);

        assertCommonSkeleton(blocks);
        assertThat(contextText(blocks))
                .isEqualTo("ℹ️ 이 제안은 다른 메시지에서 처리 중이에요. 결과는 먼저 누른 메시지에서 확인해주세요.");
        assertNoButtons(blocks);
    }

    @Test
    @DisplayName("제목에 따옴표 등 JSON 특수문자가 있어도 깨지지 않은 JSON이 만들어진다")
    void titleWithJsonSpecialCharacters() throws Exception {
        String title = "\"빠르게\" 걷기 \\ 10분";

        JsonNode blocks = blocks(cards.processing(title, QuestOfferAction.ACCEPT));

        assertThat(blocks.get(1).get("text").get("text").asText()).isEqualTo("*" + title + "*");
    }

    // ── ⑥ forDuplicateClick ─────────────────────────────────────────────

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    private static QuestOffer sentOffer() {
        QuestOffer offer = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, UUID.randomUUID(), TITLE);
        offer.markSent("D0123456789", "1732147200.000100", NOW);
        return offer;
    }

    /** 카드 1의 첫 클릭이 접수된 상태(PROCESSING) */
    private static QuestOffer processingOffer(QuestOfferAction firstClick) {
        QuestOffer offer = sentOffer();
        offer.requestDecision(firstClick, "D0123456789", "card-1-ts", null, NOW);
        offer.claim(NOW, Duration.ofSeconds(30));
        return offer;
    }

    @Test
    @DisplayName("⑥-a forDuplicateClick — 첫 클릭이 아직 처리 중(PROCESSING)이면 '다른 메시지에서 처리 중' 카드를 고른다")
    void duplicateClickWhileProcessing() {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);

        assertThat(cards.forDuplicateClick(offer)).isEqualTo(cards.handledElsewhere(TITLE));
    }

    @ParameterizedTest
    @CsvSource({
            "ACCEPT, ✅ 퀘스트를 수락했어요",
            "REJECT, 🙅 퀘스트를 거절했어요"
    })
    @DisplayName("⑥-b forDuplicateClick — 처리가 끝났으면(ACCEPTED/REJECTED) ③ 완료 카드와 같은 카드를 고른다")
    void duplicateClickAfterCompleted(QuestOfferAction firstClick, String expected) throws Exception {
        QuestOffer offer = processingOffer(firstClick);
        offer.complete();

        QuestOfferCard card = cards.forDuplicateClick(offer);

        assertThat(card).isEqualTo(cards.completed(TITLE, firstClick));
        assertThat(contextText(blocks(card))).isEqualTo(expected);
        assertNoButtons(blocks(card));
    }

    @Test
    @DisplayName("⑥-b forDuplicateClick — 확정 거절로 끝났으면(CLOSED) ④ 종료 카드와 같은 카드를 고른다")
    void duplicateClickAfterClosed() throws Exception {
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        offer.close(QuestOfferCloseReason.EXPIRED);

        QuestOfferCard card = cards.forDuplicateClick(offer);

        assertThat(card).isEqualTo(cards.closed(TITLE, QuestOfferCloseReason.EXPIRED));
        assertThat(contextText(blocks(card))).isEqualTo("⌛ 만료된 제안이라 처리할 수 없어요");
    }

    @Test
    @DisplayName("⑥-b forDuplicateClick — 카드 2에서 누른 버튼이 아니라 DB에 기록된 첫 클릭의 결과로 보여준다")
    void duplicateClickShowsRecordedResultNotClickedButton() throws Exception {
        // 카드 1에서 수락 -> 접수
        QuestOffer offer = processingOffer(QuestOfferAction.ACCEPT);
        // 카드 2에서 거절 -> 중복 클릭이라 무시된다 (DB는 그대로 ACCEPT)
        boolean accepted = offer.requestDecision(QuestOfferAction.REJECT, "D0123456789", "card-2-ts", null, NOW);
        offer.complete();

        QuestOfferCard card = cards.forDuplicateClick(offer);

        assertThat(accepted).isFalse();
        assertThat(contextText(blocks(card))).isEqualTo("✅ 퀘스트를 수락했어요");
    }

    @Test
    @DisplayName("forDuplicateClick — PENDING/SENT는 중복 클릭이 아니라(정상 접수됐어야 함) 예외로 잘못된 호출을 알린다")
    void duplicateClickOnAcceptableStatusIsRejected() {
        QuestOffer pending = QuestOffer.pending(UUID.randomUUID(), SUGGESTION_ID, UUID.randomUUID(), TITLE);
        QuestOffer sent = sentOffer();

        assertThatThrownBy(() -> cards.forDuplicateClick(pending)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cards.forDuplicateClick(sent)).isInstanceOf(IllegalStateException.class);
    }
}
