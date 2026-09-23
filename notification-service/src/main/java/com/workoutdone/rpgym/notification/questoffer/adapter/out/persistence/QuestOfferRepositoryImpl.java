package com.workoutdone.rpgym.notification.questoffer.adapter.out.persistence;

import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
import com.workoutdone.rpgym.notification.questoffer.domain.repo.QuestOfferRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class QuestOfferRepositoryImpl implements QuestOfferRepository {

    private final QuestOfferJpaRepository questOfferJpaRepository;

    @Override
    public QuestOffer save(QuestOffer questOffer) {
        return questOfferJpaRepository.save(questOffer);
    }

    @Override
    public Optional<QuestOffer> findById(UUID id) {
        return questOfferJpaRepository.findById(id);
    }

    @Override
    public Optional<QuestOffer> findBySuggestionId(UUID suggestionId) {
        return questOfferJpaRepository.findBySuggestionId(suggestionId);
    }
}
