package com.workoutdone.rpgym.game.party.adapter.out.persistence;

import com.workoutdone.rpgym.game.party.domain.MemberStatus;
import com.workoutdone.rpgym.game.party.domain.aggregate.PartyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyMemberJpaRepository extends JpaRepository<PartyMember, UUID> {

    Optional<PartyMember> findFirstByUserIdAndStatus(UUID userId, MemberStatus status);

    Optional<PartyMember> findFirstByPartyIdAndUserIdAndStatus(UUID partyId, UUID userId, MemberStatus status);

    List<PartyMember> findByPartyIdAndStatusOrderByJoinedAtAsc(UUID partyId, MemberStatus status);



    @Query("select m.userId from PartyMember m where m.partyId = :partyId and m.status = :status order by m.joinedAt asc")
    List<UUID> findUserIdsByPartyIdAndStatus(@Param("partyId") UUID partyId,
                                             @Param("status") MemberStatus status);



    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
       update PartyMember m
       set m.status = :left, m.leftAt = :leftAt
       where m.partyId = :partyId
       and m.status = :active
""")
    int leaveAll(@Param("partyId") UUID partyId,
                 @Param("active") MemberStatus active,
                 @Param("left") MemberStatus left,
                 @Param("leftAt") Instant leftAt);




    /*
     * 주간 파티 XP.
     *
     * 파티 XP 를 따로 저장하지 않는다. xp_ledgers 를 "소속 기간 ∩ 주간 창" 으로 잘라 합산한다.
     *   - 탈퇴자: joined_at ~ left_at 사이 기여분은 남는다
     *   - 중간 합류자: joined_at 이전 XP 는 안 딸려온다
     * 주석의 "중간 합류자"는 파티 진행 중간이 아니라 주간 랭킹 창 중간에를 말함
     * 조인 조건 l.occurred_at >= pm.joined_at 이 없으면 그 XP까지 파티 점수에 딸려옴
     * 별도 분기 없이 조인 조건 하나로 둘 다 성립한다. party_members 를 soft delete 로 둔 이유다.
     *
     * occurred_at (= measuredAt, 실제 활동 시각) 기준이라 지연 도착분도 올바른 주에 귀속된다.
     * SUM 이 NULL 이면 0 (아무도 XP 를 못 받은 주).
     */
    @Query(value = """
            select COALESCE(SUM(l.amount), 0)
              from game_service.party_members pm
              join game_service.xp_ledgers l
                on l.user_id = pm.user_id
               and l.occurred_at >= pm.joined_at
               and (pm.left_at IS NULL OR l.occurred_at < pm.left_at)
             where pm.party_id = :partyId
               and l.occurred_at >= :weekStart
               and l.occurred_at <  :weekEnd
            """, nativeQuery = true)
    Long sumWeeklyXp(@Param("partyId") UUID partyId,
                     @Param("weekStart") Instant weekStart,
                     @Param("weekEnd") Instant weekEnd);









    @Query("select m.userId from PartyMember m where m.partyId = :partyId and m.status = :status and m.leftAt = :leftAt order by m.joinedAt asc")
    List<UUID> findUserIdsByPartyIdAndStatusAndLeftAt(@Param("partyId") UUID partyId,
                                                      @Param("status") MemberStatus status,
                                                      @Param("leftAt") Instant leftAt);

    /*
     * 완주한 파티 수. 파티 업적의 원본 값.
     * left_at = ends_at 조건이 핵심 -- 같은 ENDED 파티라도 중간에 나간 사람은 left_at 이 앞서서 안 잡힌다.
     */
    @Query(value = """
            select count(*)
              from game_service.party_members pm
              join game_service.parties p on p.id = pm.party_id
             where pm.user_id = :userId
               and p.status = 'ENDED'
               and pm.left_at = p.ends_at
            """, nativeQuery = true)
    long countCompletedByUserId(@Param("userId") UUID userId);
}
