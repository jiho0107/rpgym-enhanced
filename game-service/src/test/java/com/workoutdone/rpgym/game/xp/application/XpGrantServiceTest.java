package com.workoutdone.rpgym.game.xp.application;

import com.workoutdone.rpgym.game.xp.domain.SourceType;
import com.workoutdone.rpgym.game.xp.domain.aggregate.Wallet;
import com.workoutdone.rpgym.game.xp.domain.aggregate.XpLedger;
import com.workoutdone.rpgym.game.xp.domain.repo.WalletRepository;
import com.workoutdone.rpgym.game.xp.domain.repo.XpLedgerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XpGrantServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T09:30:00Z");

    private XpLedgerRepository xpLedgerRepository;
    private WalletRepository walletRepository;
    private ApplicationEventPublisher events;
    private XpGrantService service;

    @BeforeEach
    void setUp() {
        xpLedgerRepository = mock(XpLedgerRepository.class);
        walletRepository = mock(WalletRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new XpGrantService(xpLedgerRepository, walletRepository, events);
        when(xpLedgerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("이미 지급된 건이면 예외 없이 건너뛰고 원장 · 지갑 · 이벤트를 건드리지 않는다")
    void 이미_지급된_건은_조용히_건너뛴다() {
        when(xpLedgerRepository.existsBySource(USER_ID, SourceType.QUEST, SOURCE_ID)).thenReturn(true);

        Optional<XpLedger> result = service.grant(USER_ID, SourceType.QUEST, SOURCE_ID, 5, OCCURRED_AT);

        assertTrue(result.isEmpty());
        verify(xpLedgerRepository, never()).save(any());
        verify(walletRepository, never()).addXp(any(), anyInt());
        verify(walletRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("지갑이 있으면 읽지 않고 원자적 UPDATE 한 번으로 더한다")
    void 지갑이_있으면_원자적으로_더한다() {
        when(walletRepository.addXp(USER_ID, 5)).thenReturn(1);

        Optional<XpLedger> result = service.grant(USER_ID, SourceType.QUEST, SOURCE_ID, 5, OCCURRED_AT);

        assertTrue(result.isPresent());
        verify(walletRepository).addXp(USER_ID, 5);
        verify(walletRepository, never()).findByUserId(any());
        verify(walletRepository, never()).save(any());
        verify(events).publishEvent(any(XpGranted.class));
    }

    @Test
    @DisplayName("첫 지급이라 지갑이 없으면 지급액으로 지갑을 만든다")
    void 첫_지급이면_지갑을_만든다() {
        when(walletRepository.addXp(USER_ID, 30)).thenReturn(0);

        service.grant(USER_ID, SourceType.PARTY_QUEST, SOURCE_ID, 30, OCCURRED_AT);

        ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(30, captor.getValue().getXp());
    }
}
