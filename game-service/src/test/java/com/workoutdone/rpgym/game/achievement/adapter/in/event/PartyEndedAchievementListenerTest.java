package com.workoutdone.rpgym.game.achievement.adapter.in.event;

import com.workoutdone.rpgym.game.achievement.application.PartyAchievementService;
import com.workoutdone.rpgym.game.party.application.PartyEnded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 리스너의 책임은 둘 -- 이벤트의 값을 그대로 넘기고, 실패해도 발행자(파티 종료 배치)를 깨뜨리지 않는다.
 * AFTER_COMMIT · REQUIRES_NEW 는 애노테이션이라 여기서 못 본다. 그건 통합 테스트 몫.
 */
class PartyEndedAchievementListenerTest {

    private final PartyAchievementService service = mock(PartyAchievementService.class);
    private final PartyEndedAchievementListener listener = new PartyEndedAchievementListener(service);

    @Test
    @DisplayName("PartyEnded 의 partyId · endedAt 이 그대로 서비스로 넘어간다")
    void delegates() {
        UUID partyId = UUID.randomUUID();
        Instant endedAt = Instant.parse("2026-09-22T00:00:00Z");

        listener.on(new PartyEnded(partyId, endedAt));

        verify(service).recordPartyCompleted(partyId, endedAt);
    }

    @Test
    @DisplayName("서비스가 던져도 리스너는 삼킨다 — AFTER_COMMIT 예외는 endParty 호출자에게 전파되기 때문")
    void swallowsFailure() {
        given(service.recordPartyCompleted(any(), any())).willThrow(new RuntimeException("db down"));

        assertThatCode(() -> listener.on(new PartyEnded(UUID.randomUUID(), Instant.now())))
                .doesNotThrowAnyException();
    }
}
