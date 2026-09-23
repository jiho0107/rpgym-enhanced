package com.workoutdone.rpgym.notification.questoffer;

import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
import com.workoutdone.rpgym.notification.questoffer.domain.repo.QuestOfferRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 테스트용 인메모리 quest_offers 저장소.
// QuestOfferService를 mock으로 대체하지 않고 실제로 동작시켜야만 검증되는 시나리오(중복 소비, 재시도)에서
// QuestOfferRepository 자리에 넣어 쓴다. game-service의 FakeRankingStore와 같은 방식이다.
public class FakeQuestOfferRepository implements QuestOfferRepository {

    private final Map<UUID, QuestOffer> offers = new HashMap<>();

    @Override
    public QuestOffer save(QuestOffer questOffer) {
        offers.put(questOffer.getId(), questOffer);
        return questOffer;
    }

    @Override
    public Optional<QuestOffer> findById(UUID id) {
        return Optional.ofNullable(offers.get(id));
    }

    @Override
    public Optional<QuestOffer> findBySuggestionId(UUID suggestionId) {
        return offers.values().stream()
                .filter(offer -> offer.getSuggestionId().equals(suggestionId))
                .findFirst();
    }

    public List<QuestOffer> findAll() {
        return List.copyOf(offers.values());
    }
}
