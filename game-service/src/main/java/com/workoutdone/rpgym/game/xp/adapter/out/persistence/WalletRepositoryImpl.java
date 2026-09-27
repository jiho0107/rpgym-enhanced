package com.workoutdone.rpgym.game.xp.adapter.out.persistence;

import com.workoutdone.rpgym.game.xp.domain.aggregate.Wallet;
import com.workoutdone.rpgym.game.xp.domain.repo.WalletRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WalletRepositoryImpl implements WalletRepository {

    private final WalletJpaRepository walletJpaRepository;

    @Override
    public Optional<Wallet> findByUserId(UUID userId) {
        return walletJpaRepository.findById(userId);
    }

    @Override
    public Wallet save(Wallet wallet) {
        return walletJpaRepository.save(wallet);
    }

    @Override
    public int addXp(UUID userId, int amount) {
        return walletJpaRepository.addXp(userId, amount);
    }
}
