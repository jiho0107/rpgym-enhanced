package com.workoutdone.rpgym.game.xp.adapter.out.persistence;

import com.workoutdone.rpgym.game.xp.domain.aggregate.Wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface WalletJpaRepository extends JpaRepository<Wallet, UUID> {

    // xp = xp + :amount 를 한 문장으로 실행한다.
    // UPDATE 가 그 행에 락을 잡고, 먼저 잡은 트랜잭션이 커밋될 때까지 뒤 트랜잭션은 기다렸다가
    // 커밋된 최신 값 위에 더한다. 그래서 두 트랜잭션이 동시에 와도 증가분이 사라지지 않는다.
    // 네이티브 쿼리인 이유 -> updated_at 은 JPA Auditing 이 채우는데 벌크 UPDATE 는 Auditing 을 거치지 않는다.
    // 직접 갱신해야 한다. local 프로파일(H2, PostgreSQL 모드)에서도 그대로 돈다.
    @Modifying
    @Query(value = "UPDATE game_service.wallets "
            + "SET xp = xp + :amount, updated_at = CURRENT_TIMESTAMP "
            + "WHERE user_id = :userId", nativeQuery = true)
    // int인 이유: 쿼리로 인해 실제 영향받은(수정된) 행의 개수 리턴
    int addXp(@Param("userId") UUID userId, @Param("amount") int amount);
}
