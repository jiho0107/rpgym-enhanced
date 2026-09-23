package com.workoutdone.rpgym.game.party.adapter.out.persistence;

import com.workoutdone.rpgym.game.party.domain.MemberStatus;
import com.workoutdone.rpgym.game.party.domain.aggregate.Party;
import com.workoutdone.rpgym.game.party.domain.aggregate.PartyMember;
import com.workoutdone.rpgym.game.party.domain.repo.PartyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PartyMemberRepositoryImpl implements PartyMemberRepository {


    private final PartyMemberJpaRepository jpa;

    @Override
    public PartyMember save(PartyMember member) {
        // saveAndFlush: uk_party_members_active_one_party 위반을 커밋 시점이 아니라 여기서 터뜨린다.
        // 그래야 MemberEnroller(6단계)가 잡아서 409 로 바꿀 수 있다.
        return jpa.saveAndFlush(member);
    }



    @Override
    public Optional<PartyMember> findActiveByUserId(UUID userId){
        return jpa.findFirstByUserIdAndStatus(userId, MemberStatus.ACTIVE);
    }
    @Override
    public Optional<PartyMember> findActiveByPartyIdAndUserId(UUID partyId, UUID userId) {
        return jpa.findFirstByPartyIdAndUserIdAndStatus(partyId, userId, MemberStatus.ACTIVE);
    }

    @Override
    public List<PartyMember> findActiveByPartyId(UUID partyId) {
        return jpa.findByPartyIdAndStatusOrderByJoinedAtAsc(partyId, MemberStatus.ACTIVE);
    }

    @Override
    public List<UUID> findActiveUserIdsByPartyId(UUID partyId) {
        return jpa.findUserIdsByPartyIdAndStatus(partyId, MemberStatus.ACTIVE);
    }

    @Override
    public int leaveAllActive(UUID partyId, Instant leftAt) {
        return jpa.leaveAll(partyId, MemberStatus.ACTIVE, MemberStatus.LEFT, leftAt);
    }

    @Override
    public long sumWeeklyXp(UUID partyId, Instant weekStart, Instant weekEnd) {
        Long sum = jpa.sumWeeklyXp(partyId, weekStart, weekEnd);
        return sum == null ? 0L : sum;
    }

    @Override
    public List<UUID> findUserIdsCompletedAt(UUID partyId, Instant endedAt) {
        return jpa.findUserIdsByPartyIdAndStatusAndLeftAt(partyId, MemberStatus.LEFT, endedAt);
    }

    @Override
    public long countCompletedByUserId(UUID userId) {
        return jpa.countCompletedByUserId(userId);
    }
}
