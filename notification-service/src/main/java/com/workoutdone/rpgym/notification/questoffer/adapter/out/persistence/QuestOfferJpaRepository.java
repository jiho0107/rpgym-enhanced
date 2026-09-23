package com.workoutdone.rpgym.notification.questoffer.adapter.out.persistence;

import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QuestOfferJpaRepository extends JpaRepository<QuestOffer, UUID> {

    Optional<QuestOffer> findBySuggestionId(UUID suggestionId);
}
