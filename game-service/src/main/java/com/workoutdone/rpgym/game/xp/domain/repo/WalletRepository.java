package com.workoutdone.rpgym.game.xp.domain.repo;

import java.util.Optional;
import java.util.UUID;

import com.workoutdone.rpgym.game.xp.domain.aggregate.Wallet;

public interface WalletRepository {
	Optional<Wallet> findByUserId(UUID userId);
	Wallet save(Wallet wallet);

	// 지갑 XP 를 DB 안에서 원자적으로 더한다. 갱신된 행 수를 돌려준다 (지갑이 없으면 0).
	// 애플리케이션에서 읽고 더해서 쓰지 않는다 ,그 사이에 다른 트랜잭션이 끼면 한쪽 증가분이 사라진다.
	int addXp(UUID userId, int amount);
}
