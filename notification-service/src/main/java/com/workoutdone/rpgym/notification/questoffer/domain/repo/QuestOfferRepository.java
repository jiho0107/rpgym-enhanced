package com.workoutdone.rpgym.notification.questoffer.domain.repo;

import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import java.util.Optional;
import java.util.UUID;

public interface QuestOfferRepository {

    QuestOffer save(QuestOffer questOffer);

    Optional<QuestOffer> findById(UUID id);

    Optional<QuestOffer> findBySuggestionId(UUID suggestionId);
}
