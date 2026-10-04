package com.workoutdone.rpgym.game.quest.application;

import com.workoutdone.rpgym.game.outbox.application.OutboxRecorder;
import com.workoutdone.rpgym.game.outbox.domain.AggregateType;
import com.workoutdone.rpgym.game.outbox.domain.OutboxEventType;
import com.workoutdone.rpgym.game.quest.application.payload.PartyQuestCompletedData;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuest;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuestMember;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestMemberRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestRepository;
import com.workoutdone.rpgym.game.quest.domain.vo.ContributionResult;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;
import com.workoutdone.rpgym.game.xp.application.XpGrantService;
import com.workoutdone.rpgym.game.xp.domain.SourceType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 파티 퀘스트 판정의 순서와 조건을 본다.
// 실제 동시 실행은 여기서 재현할 수 없다. 조건부 UPDATE 를 대체해두고,
// 그것이 돌려준 값에 따라 무엇을 하고 무엇을 안 하는지만 확인한다.
// 네 명이 진짜로 동시에 들어올 때 하나만 통과하는지는 실제 DB 로 하는 동시성 실험이 증명한다.
class PartyQuestProgressServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);
    private static final Instant STARTED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-21T14:59:59Z");
    private static final Instant MEASURED_AT = Instant.parse("2026-09-21T02:00:00Z");
    private static final UUID PARTY_QUEST_ID = UUID.randomUUID();
    private static final UUID PARTY_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final int TARGET = 4000;
    private static final int REWARD_XP = 30;

    private PartyQuestRepository partyQuestRepository;
    private PartyQuestMemberRepository memberRepository;
    private XpGrantService xpGrantService;
    private OutboxRecorder outboxRecorder;
    private PartyQuestProgressService service;

    private PartyQuest partyQuest;
    private PartyQuestMember myRow;

    @BeforeEach
    void setUp() {
        partyQuestRepository = mock(PartyQuestRepository.class);
        memberRepository = mock(PartyQuestMemberRepository.class);
        xpGrantService = mock(XpGrantService.class);
        outboxRecorder = mock(OutboxRecorder.class);
        service = new PartyQuestProgressService(
                partyQuestRepository, memberRepository, xpGrantService, outboxRecorder);

        partyQuest = PartyQuest.create(
                PARTY_QUEST_ID, PARTY_ID, "퇴근길 함께 4000보", Metric.STEPS,
                TARGET, REWARD_XP, STARTED_AT, EXPIRES_AT);

        // 기준 3000 에 이미 1800 을 쌓아둔 상태
        myRow = PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, USER_ID, 3000);
        myRow.apply(new Snapshot(DATE, Instant.parse("2026-09-21T01:00:00Z"), 4800, 0, 0), Metric.STEPS, DATE);

        when(partyQuestRepository.findActiveByUserId(eq(USER_ID), any()))
                .thenReturn(Optional.of(partyQuest));
        when(memberRepository.findByPartyQuestIdAndUserId(PARTY_QUEST_ID, USER_ID))
                .thenReturn(Optional.of(myRow));
        when(partyQuestRepository.claimCompletion(any(), any())).thenReturn(0);
    }

    private Snapshot snapshot(int steps) {
        return new Snapshot(DATE, MEASURED_AT, steps, 0, 0);
    }

    private List<PartyQuestMember> fourMembers() {
        return List.of(
                myRow,
                PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, UUID.randomUUID(), 1200),
                PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, UUID.randomUUID(), 500),
                PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, UUID.randomUUID(), 800));
    }

    @Test
    @DisplayName("살아 있는 파티 퀘스트가 없으면 아무것도 하지 않는다")
    void 파티_퀘스트가_없으면_끝낸다() {
        when(partyQuestRepository.findActiveByUserId(any(), any())).thenReturn(Optional.empty());

        assertTrue(service.apply(USER_ID, snapshot(5200)).isEmpty());

        verifyNoInteractions(memberRepository, xpGrantService, outboxRecorder);
    }

    @Test
    @DisplayName("기여가 반영되면 공유 카운터에 증분만큼 더한다")
    void 카운터에_증분만큼_더한다() {
        service.apply(USER_ID, snapshot(5200));

        // 새 기여 2200, 이전 기여 1800 이므로 더할 값은 400 이다
        verify(partyQuestRepository).addToCurrentValue(PARTY_QUEST_ID, 400);
        verify(memberRepository).save(myRow);
        assertEquals(2200, myRow.getContributedVal());
    }

    @Test
    @DisplayName("더할 것이 없으면 공유 카운터를 건드리지 않는다 — 경합 지점에 헛된 잠금을 걸지 않는다")
    void 증분이_0이면_카운터를_안_건드린다() {
        // 기준이 아직 없는 멤버의 첫 이벤트. 기준만 정하고 기여는 0 이다
        PartyQuestMember fresh = PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, USER_ID, null);
        when(memberRepository.findByPartyQuestIdAndUserId(PARTY_QUEST_ID, USER_ID))
                .thenReturn(Optional.of(fresh));

        service.apply(USER_ID, snapshot(4000));

        assertEquals(4000, fresh.getBaselineVal());
        verify(partyQuestRepository, never()).addToCurrentValue(any(), anyInt());
        verify(partyQuestRepository, never()).claimCompletion(any(), any());
        verifyNoInteractions(xpGrantService, outboxRecorder);
    }

    @Test
    @DisplayName("중복 이벤트는 저장도 카운터 갱신도 하지 않는다")
    void 중복은_아무것도_하지_않는다() {
        // 이미 반영한 시각과 같은 스냅샷
        Snapshot duplicate = new Snapshot(DATE, Instant.parse("2026-09-21T01:00:00Z"), 4800, 0, 0);

        Optional<ContributionResult> result = service.apply(USER_ID, duplicate);

        assertInstanceOf(ContributionResult.Ignored.class, result.orElseThrow());
        verify(memberRepository, never()).save(any());
        verify(partyQuestRepository, never()).addToCurrentValue(any(), anyInt());
        verifyNoInteractions(xpGrantService, outboxRecorder);
    }

    @Test
    @DisplayName("퀘스트 날짜가 아닌 스냅샷은 저장도 카운터 갱신도 하지 않는다 — 늦게 온 어제 이벤트가 공짜 완료를 만든다")
    void 다른_날_스냅샷은_아무것도_하지_않는다() {
        // 생성 뒤 아직 이벤트를 하나도 받지 않아 워터마크가 비어 있는 멤버다. 중복 검사로는 걸러지지 않는다.
        PartyQuestMember fresh = PartyQuestMember.join(UUID.randomUUID(), PARTY_QUEST_ID, USER_ID, 3000);
        when(memberRepository.findByPartyQuestIdAndUserId(PARTY_QUEST_ID, USER_ID))
                .thenReturn(Optional.of(fresh));
        // 어제 밤 누적값이다. 반영하면 8000 - 3000 = 5000 이 기여로 잡혀 목표 4000 을 넘는다
        Snapshot yesterday = new Snapshot(
                DATE.minusDays(1), Instant.parse("2026-09-20T14:30:00Z"), 8000, 0, 0);

        Optional<ContributionResult> result = service.apply(USER_ID, yesterday);

        assertInstanceOf(ContributionResult.Ignored.class, result.orElseThrow());
        assertEquals(0, fresh.getContributedVal());
        verify(memberRepository, never()).save(any());
        verify(partyQuestRepository, never()).addToCurrentValue(any(), anyInt());
        verify(partyQuestRepository, never()).claimCompletion(any(), any());
        verifyNoInteractions(xpGrantService, outboxRecorder);
    }

    @Test
    @DisplayName("완료 선점에 실패하면 XP도 이벤트도 없다 — 중복 지급을 막는 지점이다")
    void 선점에_실패하면_보상이_없다() {
        // 다른 멤버의 트랜잭션이 먼저 완료를 가져간 상황이다.
        // 이 값을 확인하지 않고 지급하면 네 명이 전부 지급해서 XP 가 네 배로 나간다.
        when(partyQuestRepository.claimCompletion(any(), any())).thenReturn(0);

        service.apply(USER_ID, snapshot(5200));

        verify(partyQuestRepository).addToCurrentValue(PARTY_QUEST_ID, 400);
        verifyNoInteractions(xpGrantService, outboxRecorder);
    }

    @Test
    @DisplayName("완료를 선점하면 멤버 수만큼 XP를 지급한다")
    void 선점하면_멤버_전원에게_지급한다() {
        when(partyQuestRepository.claimCompletion(any(), any())).thenReturn(1);
        when(partyQuestRepository.findById(PARTY_QUEST_ID)).thenReturn(Optional.of(partyQuest));
        when(memberRepository.findByPartyQuestId(PARTY_QUEST_ID)).thenReturn(fourMembers());

        service.apply(USER_ID, snapshot(5200));

        // 네 명에게 한 건씩. 같은 멤버에게 두 건이 아니므로 중복이 아니다
        verify(xpGrantService, times(4)).grant(
                any(), eq(SourceType.PARTY_QUEST), eq(PARTY_QUEST_ID), eq(REWARD_XP), eq(MEASURED_AT));
    }

    @Test
    @DisplayName("완료 이벤트에 제목과 멤버 명단이 실린다")
    void 완료_이벤트에_명단이_실린다() {
        when(partyQuestRepository.claimCompletion(any(), any())).thenReturn(1);
        when(partyQuestRepository.findById(PARTY_QUEST_ID)).thenReturn(Optional.of(partyQuest));
        when(memberRepository.findByPartyQuestId(PARTY_QUEST_ID)).thenReturn(fourMembers());

        ArgumentCaptor<PartyQuestCompletedData> payload =
                ArgumentCaptor.forClass(PartyQuestCompletedData.class);

        service.apply(USER_ID, snapshot(5200));

        verify(outboxRecorder).append(
                eq(AggregateType.PARTY_QUEST),
                eq(PARTY_QUEST_ID),
                eq(OutboxEventType.PARTY_QUEST_COMPLETED),
                eq(USER_ID),
                eq(MEASURED_AT),
                payload.capture());

        PartyQuestCompletedData data = payload.getValue();
        assertEquals("퇴근길 함께 4000보", data.title());
        assertEquals(TARGET, data.targetValue());
        assertEquals(REWARD_XP, data.rewardXp());
        // 알림 쪽이 이 명단을 돌면서 각자에게 알린다
        assertEquals(4, data.members().size());

        // 명단 순서는 중요하지않음. 지급 전에 userId 로 정렬하므로(락 순서 고정) 몇 번째인지로 찾으면
        // 랜덤 UUID 에 따라 통과했다 실패했다 한다. 순서가 아니라 "누구인지" 로 찾는다.
        PartyQuestCompletedData.Member me = data.members().stream()
                .filter(member -> member.userId().equals(USER_ID))
                .findFirst()
                .orElseThrow();
        assertEquals(2200, me.contributedValue());
    }

    @Test
    @DisplayName("보상은 userId 오름차순으로 지급한다 -- 지갑 락을 잡는 순서를 모든 트랜잭션에서 같게 만든다")
    void 보상은_userId_순서로_지급한다() {
        when(partyQuestRepository.claimCompletion(any(), any())).thenReturn(1);
        when(partyQuestRepository.findById(PARTY_QUEST_ID)).thenReturn(Optional.of(partyQuest));
        // fourMembers() 는 부를 때마다 새 UUID 를 만든다. 기대 순서와 비교하려면 한 번만 만든다.
        List<PartyQuestMember> members = fourMembers();
        when(memberRepository.findByPartyQuestId(PARTY_QUEST_ID)).thenReturn(members);

        service.apply(USER_ID, snapshot(5200));

        List<UUID> expectedOrder = members.stream()
                .map(PartyQuestMember::getUserId)
                .sorted()
                .toList();
        InOrder inOrder = inOrder(xpGrantService);
        for (UUID userId : expectedOrder) {
            inOrder.verify(xpGrantService).grant(
                    eq(userId), eq(SourceType.PARTY_QUEST), eq(PARTY_QUEST_ID), eq(REWARD_XP), eq(MEASURED_AT));
        }
    }

    @Test
    @DisplayName("멤버 행을 찾지 못하면 예외 대신 조용히 끝낸다 — 예외는 파티션을 멈춘다")
    void 멤버_행이_없으면_조용히_끝낸다() {
        when(memberRepository.findByPartyQuestIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertTrue(service.apply(USER_ID, snapshot(5200)).isEmpty());

        verify(partyQuestRepository, never()).addToCurrentValue(any(), anyInt());
        verifyNoInteractions(xpGrantService, outboxRecorder);
    }
}
