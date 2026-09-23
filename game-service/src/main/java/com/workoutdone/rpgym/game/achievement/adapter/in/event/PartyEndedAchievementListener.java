package com.workoutdone.rpgym.game.achievement.adapter.in.event;

import com.workoutdone.rpgym.game.achievement.application.PartyAchievementService;
import com.workoutdone.rpgym.game.party.application.PartyEnded;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 파티 종료를 파티 업적으로 잇는 인바운드 어댑터. ranking 의 XpGrantedEventListener 와 같은 규약이다.
 *
 * party 는 업적을 모른다. PartyEnded 를 쏠 뿐이고 이쪽이 구독한다. 의존은 achievement → party 한 방향.
 * Kafka 가 아닌 이유: 같은 JVM 이다. 브로커를 거치면 지연 · 직렬화 · 컨슈머 그룹만 생기고 얻는 게 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartyEndedAchievementListener {

    private final PartyAchievementService partyAchievementService;

    /**
     * AFTER_COMMIT: endParty 트랜잭션(멤버 LEFT · 파티 ENDED)이 커밋된 뒤에만 돈다.
     * 그 전에 돌면 party_members 의 left_at 이 아직 안 보여 완주자가 0명이다.
     *
     * 여기에 @Transactional 을 걸지 않는다. 경계는 PartyAchievementService 의 REQUIRES_NEW 다.
     * 걸면 서비스의 예외가 물리 트랜잭션을 rollback-only 로 만들고, 아래 catch 가 삼켜도
     * 프록시 커밋에서 UnexpectedRollbackException 이 터진다 (랭킹에서 실제로 겪은 문제).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PartyEnded event) {
        try {
            partyAchievementService.recordPartyCompleted(event.partyId(), event.endedAt());
        } catch (Exception e) {
            /*
             * 업적 실패가 파티 종료를 되돌리거나 종료 배치를 멈춰선 안 된다.
             * AFTER_COMMIT 리스너의 예외는 커밋 호출자(PartyLifecycleBatch.endParty)에게 전파되므로 여기서 삼킨다.
             *
             * 삼켜도 잃는 게 없다 -- 완주 기록의 원본은 party_members 에 그대로 있다.
             * 다음 PartyEnded 에서 절대값으로 다시 세어 덮어쓰므로 그때 따라잡는다.
             */
            log.error("파티 업적 반영 실패. partyId={} endedAt={}", event.partyId(), event.endedAt(), e);
        }
    }
}
