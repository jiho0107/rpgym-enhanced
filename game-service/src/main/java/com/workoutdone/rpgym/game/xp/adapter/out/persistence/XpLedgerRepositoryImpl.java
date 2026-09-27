package com.workoutdone.rpgym.game.xp.adapter.out.persistence;

import com.workoutdone.rpgym.game.xp.domain.SourceType;
import com.workoutdone.rpgym.game.xp.domain.aggregate.XpLedger;
import com.workoutdone.rpgym.game.xp.domain.repo.XpLedgerRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class XpLedgerRepositoryImpl implements XpLedgerRepository {

    private final XpLedgerJpaRepository xpLedgerJpaRepository;

    @Override
    public XpLedger save(XpLedger ledger) {
        return xpLedgerJpaRepository.save(ledger);
    }

    @Override
    public boolean existsBySource(UUID userId, SourceType sourceType, UUID sourceId) {
        return xpLedgerJpaRepository.existsByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId);
    }
}
