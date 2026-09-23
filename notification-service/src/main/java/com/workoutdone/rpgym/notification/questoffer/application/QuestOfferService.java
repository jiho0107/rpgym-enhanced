package com.workoutdone.rpgym.notification.questoffer.application;

import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.notification.questoffer.domain.NotificationErrorCode;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
import com.workoutdone.rpgym.notification.questoffer.domain.repo.QuestOfferRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * quest_offers 쓰기 전용. Slack 호출은 이 클래스 밖(GameEventConsumer)에서 일어난다.
 *
 * prepareForSend와 markSent를 하나의 트랜잭션으로 묶지 않는 이유
 * -- 그 사이에 Slack API 호출(외부 I/O)이 끼기 때문이다.
 *  묶으면 Slack 호출 실패 시 PENDING 삽입까지 같이 롤백되어 "발송을 시도했다"는 기록 자체가 사라지고,
 *  재시도 때 멱등성 체크(suggestionId 존재 여부)가 무력화된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestOfferService {

    private final QuestOfferRepository questOfferRepository;

    /**
     * @return 발송을 진행해야 하면 quest_offers의 id, 이미 처리된 제안이라 건너뛰어야 하면 empty
     */
    @Transactional
    public Optional<UUID> prepareForSend(UUID suggestionId, UUID userId) {
        Optional<QuestOffer> existing = questOfferRepository.findBySuggestionId(suggestionId);
        if (existing.isPresent()) {
            QuestOffer offer = existing.get();
            if (!offer.isPending()) {
                // 이미 SENT 이상 -- 진짜 중복(재전송, 리밸런싱 중 중복 소비)이니 스킵한다.
                log.debug("이미 처리된 제안이라 재발송을 건너뛴다. suggestionId={} status={}",
                        suggestionId, offer.getStatus());
                return Optional.empty();
            }
            // PENDING인 채로 남아있다 -- 직전 시도에서 Slack 발송이 실패했을 뿐이니 다시 시도한다.
            return Optional.of(offer.getId());
        }

        QuestOffer created = questOfferRepository.save(QuestOffer.pending(UUID.randomUUID(), suggestionId, userId));
        return Optional.of(created.getId());
    }

    @Transactional
    public void markSent(UUID offerId, String slackDmChannelId, String slackMessageTs) {
        QuestOffer offer = questOfferRepository.findById(offerId)
                .orElseThrow(() -> new BaseException(NotificationErrorCode.QUEST_OFFER_NOT_FOUND));
        offer.markSent(slackDmChannelId, slackMessageTs, Instant.now());
    }

    /**
     * Slack 버튼 클릭 처리(SlackInteractionController)에서 쓴다.
     * slack 발송 시점에 quest_offers 테이블에 저장해둔 userId를 그대로 꺼내 쓰므로, 여기서 user-service를 다시 조회할 필요가 없다.
     */
    @Transactional(readOnly = true)
    public QuestOffer getBySuggestionId(UUID suggestionId) {
        return questOfferRepository.findBySuggestionId(suggestionId)
                .orElseThrow(() -> new BaseException(NotificationErrorCode.QUEST_OFFER_NOT_FOUND));
    }
}
