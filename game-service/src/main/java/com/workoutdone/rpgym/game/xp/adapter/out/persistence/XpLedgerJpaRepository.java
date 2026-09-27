package com.workoutdone.rpgym.game.xp.adapter.out.persistence;

import com.workoutdone.rpgym.game.xp.domain.SourceType;
import com.workoutdone.rpgym.game.xp.domain.aggregate.XpLedger;

import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface XpLedgerJpaRepository extends Repository<XpLedger, UUID> {

    XpLedger save(XpLedger ledger);

    boolean existsByUserIdAndSourceTypeAndSourceId(UUID userId, SourceType sourceType, UUID sourceId);
}
