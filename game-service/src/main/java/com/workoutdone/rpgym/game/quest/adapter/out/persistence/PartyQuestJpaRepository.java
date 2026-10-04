package com.workoutdone.rpgym.game.quest.adapter.out.persistence;

import com.workoutdone.rpgym.game.quest.domain.QuestStatus;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PartyQuestJpaRepository extends JpaRepository<PartyQuest, UUID> {

    // 하나만 돌려주는 대신 목록을 돌려받고 위임 클래스에서 첫 건을 고른다.
    // Optional 로 받는 파생 쿼리는 두 건이 나오면 예외를 던지는데,
    // 이 경로는 카프카 컨슈머라서 예외가 곧 무한 재시도이고 그 파티션이 멈춘다.
    // 한 유저가 여러 파티에 속하는 것을 막는 제약이 없으므로,
    // 그런 상황에서 터지는 대신 만료가 가까운 것을 결정적으로 하나 고른다.
    @Query("select q from PartyQuest q "
            + "where q.status = :status and q.expiredAt > :at "
            + "and exists (select 1 from PartyQuestMember m "
            + "            where m.partyQuestId = q.partyQuestId and m.userId = :userId) "
            + "order by q.expiredAt asc")
    List<PartyQuest> findActiveByUserId(
            @Param("userId") UUID userId,
            @Param("status") QuestStatus status,
            @Param("at") Instant at);

    @Query("select count(q) > 0 from PartyQuest q "
            + "where q.partyId = :partyId and q.status = :status and q.expiredAt > :at")
    boolean existsActiveByPartyId(
            @Param("partyId") UUID partyId,
            @Param("status") QuestStatus status,
            @Param("at") Instant at);

    // flush..(미리 반영하기), clear..(메모리 비우기)
    // flushAutomatically 가 필요한 이유:
    // 이 문장은 영속성 컨텍스트를 거치지 않고 데이터베이스를 직접 접근함.
    // 쓴 이유는 바로 앞에서 멤버 행을 도메인 메서드로 고쳐둔 상태인데 아직 반영 전이면,
    // 순서가 뒤집혀서 멤버 행 갱신이 나중에 나간다. 즉 여기서 수행된 쿼리는 바로 DB에 수정.

    // clearAutomatically 가 필요한 이유:
    // 이 메서드가 바꾼 값을 영속성 컨텍스트는 모른다.
    // 비워두지 않으면 같은 트랜잭션에서 다시 읽을 때 옛날 값이 그대로 돌아온다.
    // 그 값으로 완료를 판단하면 틀린 값으로 보상을 결정하게 된다.

    // 공유 카운터
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PartyQuest q set q.currentVal = q.currentVal + :delta "
            + "where q.partyQuestId = :partyQuestId")
    int addToCurrentValue(@Param("partyQuestId") UUID partyQuestId, @Param("delta") int delta);

    // 조건부 UPDATE
    // 조건 세 개가 전부 이 한 문장 안에 있어야 한다.
    // 하나라도 애플리케이션으로 꺼내면 읽는 시점과 쓰는 시점 사이가 벌어져서
    // 동시에 들어온 트랜잭션이 전부 통과한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PartyQuest q set q.status = :completed "
            + "where q.partyQuestId = :partyQuestId "
            + "and q.status = :active "
            + "and q.expiredAt > :at "
            + "and q.currentVal >= q.targetVal")
    int claimCompletion(
            @Param("partyQuestId") UUID partyQuestId,
            @Param("at") Instant at,
            @Param("active") QuestStatus active,
            @Param("completed") QuestStatus completed);
}
