package com.workoutdone.rpgym.game.xp.domain.repo;

import com.workoutdone.rpgym.game.xp.domain.SourceType;
import com.workoutdone.rpgym.game.xp.domain.aggregate.XpLedger;

import java.util.UUID;

public interface XpLedgerRepository {
	XpLedger save(XpLedger ledger);

	// 조용한 검사용. 멱등키 (user_id, source_type, source_id) 로 이미 지급된 건이 있는지 본다.
	// uk_xp_ledgers_source 인덱스를 그대로 탄다.
	boolean existsBySource(UUID userId, SourceType sourceType, UUID sourceId);
}
