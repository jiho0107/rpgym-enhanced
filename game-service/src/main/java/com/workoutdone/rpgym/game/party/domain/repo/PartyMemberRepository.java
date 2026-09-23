package com.workoutdone.rpgym.game.party.domain.repo;

import com.workoutdone.rpgym.game.party.domain.aggregate.PartyMember;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyMemberRepository {

    PartyMember save(PartyMember member);

    /** 사용자의 현재 소속. 1인 1파티라 최대 한 건이다. */
    Optional<PartyMember> findActiveByUserId(UUID userId);

    Optional<PartyMember> findActiveByPartyIdAndUserId(UUID partyId, UUID userId);

    /** joined_at 오름차순. 첫 원소가 승계 1순위다. */
    List<PartyMember> findActiveByPartyId(UUID partyId);

    List<UUID> findActiveUserIdsByPartyId(UUID partyId);

    /** 파티 종료 시 전원 LEFT. left_at = 파티의 ends_at. */
    int leaveAllActive(UUID partyId, Instant leftAt);

    /** 주간 파티 XP = Σ (소속 기간 ∩ [weekStart, weekEnd) 안의 xp_ledgers.amount) */
    long sumWeeklyXp(UUID partyId, Instant weekStart, Instant weekEnd);

    /**
     * 파티를 끝까지 함께한 사람. 종료 시 leaveAllActive 가 left_at = ends_at 을 박으므로
     * "left_at 이 정확히 endedAt 인 LEFT 행" 이 완주자다. 중간 탈퇴자는 left_at 이 그보다 앞이다.
     * 업적이 PartyEnded 를 받은 뒤(커밋 후) 부른다 -- 그 시점엔 ACTIVE 가 남아 있지 않다.
     */
    List<UUID> findUserIdsCompletedAt(UUID partyId, Instant endedAt);

    /**
     * 이 유저가 완주한 파티 수. 업적 "파티 N회 완주" 의 원본 -- 이벤트마다 이 절대값을 다시 읽어 덮어쓴다.
     * 완주 = 파티가 ENDED 이고 내 left_at 이 그 파티의 ends_at 과 같다.
     */
    long countCompletedByUserId(UUID userId);
}
