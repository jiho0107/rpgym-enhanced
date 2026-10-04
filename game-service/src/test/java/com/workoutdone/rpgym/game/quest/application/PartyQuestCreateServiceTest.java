package com.workoutdone.rpgym.game.quest.application;

import com.workoutdone.rpgym.game.outbox.application.OutboxRecorder;
import com.workoutdone.rpgym.game.outbox.domain.AggregateType;
import com.workoutdone.rpgym.game.outbox.domain.OutboxEventType;
import com.workoutdone.rpgym.game.party.application.PartyErrorCode;
import com.workoutdone.rpgym.game.party.application.PartyException;
import com.workoutdone.rpgym.game.party.application.PartyQueryService;
import com.workoutdone.rpgym.game.party.application.view.PartyView;
import com.workoutdone.rpgym.game.party.domain.MemberRole;
import com.workoutdone.rpgym.game.party.domain.PartyMetric;
import com.workoutdone.rpgym.game.party.domain.PartyStatus;
import com.workoutdone.rpgym.game.party.domain.PartyVisibility;
import com.workoutdone.rpgym.game.quest.application.payload.PartyQuestCreatedData;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.QuestStatus;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuest;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuestMember;
import com.workoutdone.rpgym.game.quest.domain.aggregate.UserLatestSnapshot;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestMemberRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.UserLatestSnapshotRepository;
import com.workoutdone.rpgym.game.quest.domain.vo.Snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyQuestCreateServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final UUID PARTY_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEMBER_B = UUID.randomUUID();
    private static final UUID MEMBER_C = UUID.randomUUID();
    private static final UUID MEMBER_D = UUID.randomUUID();

    private PartyQuestRepository partyQuestRepository;
    private PartyQuestMemberRepository memberRepository;
    private UserLatestSnapshotRepository snapshotRepository;
    private PartyQueryService partyQueryService;
    private OutboxRecorder outboxRecorder;
    private PartyQuestCreateService service;

    @BeforeEach
    void setUp() {
        partyQuestRepository = mock(PartyQuestRepository.class);
        memberRepository = mock(PartyQuestMemberRepository.class);
        snapshotRepository = mock(UserLatestSnapshotRepository.class);
        partyQueryService = mock(PartyQueryService.class);
        outboxRecorder = mock(OutboxRecorder.class);
        service = new PartyQuestCreateService(
                partyQuestRepository, memberRepository, snapshotRepository,
                partyQueryService, new RewardPolicy(), outboxRecorder);

        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ACTIVE, PartyMetric.STEPS, OWNER, List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D)));
        when(partyQuestRepository.existsActiveByPartyId(any(), any())).thenReturn(false);
        when(partyQuestRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(memberRepository.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        // 네 명 중 셋만 동기화한 적이 있다
        when(snapshotRepository.findAllByUserIds(any())).thenReturn(List.of(
                snapshotOf(OWNER, 3000), snapshotOf(MEMBER_B, 1200), snapshotOf(MEMBER_C, 500)));
    }

    private PartyView party(PartyStatus status, PartyMetric metric, UUID ownerId, List<UUID> memberIds) {
        List<PartyView.MemberView> members = memberIds.stream()
                .map(id -> new PartyView.MemberView(
                        id, id.equals(ownerId) ? MemberRole.OWNER : MemberRole.MEMBER, Instant.now()))
                .toList();
        return new PartyView(
                PARTY_ID, "퇴근길 파티", ownerId, status, PartyVisibility.PUBLIC, metric,
                members.size(), 4, null, Instant.now().plusSeconds(86400), Instant.now(), members);
    }

    private UserLatestSnapshot snapshotOf(UUID userId, int steps) {
        return UserLatestSnapshot.create(userId,
                new Snapshot(LocalDate.now(KST), Instant.now(), steps, 0, 0));
    }

    private PartyQuestCreateCommand command(String title, int target) {
        return new PartyQuestCreateCommand(PARTY_ID, OWNER, title, target);
    }

    private PartyQuestCreateCommand validCommand() {
        return command("퇴근길 함께 4000보", 4000);
    }

    private static PartyQuestCreation.Reason reasonOf(PartyQuestCreation creation) {
        return assertInstanceOf(PartyQuestCreation.Failed.class, creation).reason();
    }

    private PartyQuest savedQuest() {
        ArgumentCaptor<PartyQuest> captor = ArgumentCaptor.forClass(PartyQuest.class);
        verify(partyQuestRepository).save(captor.capture());
        return captor.getValue();
    }

    private List<PartyQuestMember> savedMembers() {
        ArgumentCaptor<List<PartyQuestMember>> captor = ArgumentCaptor.forClass(List.class);
        verify(memberRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private PartyQuestCreatedData recordedPayload() {
        ArgumentCaptor<PartyQuestCreatedData> captor = ArgumentCaptor.forClass(PartyQuestCreatedData.class);
        verify(outboxRecorder).append(any(), any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("생성되면 멤버 수만큼 행이 생기고 기준값이 저장된 스냅샷에서 온다")
    void 기준값은_스냅샷에서_온다() {
        PartyQuestCreation creation = service.create(validCommand());

        assertInstanceOf(PartyQuestCreation.Created.class, creation);
        List<PartyQuestMember> members = savedMembers();
        assertEquals(4, members.size());
        assertEquals(3000, members.get(0).getBaselineVal());
        assertEquals(1200, members.get(1).getBaselineVal());
        assertEquals(500, members.get(2).getBaselineVal());
        // 모두 기여 0 에서 시작한다
        assertEquals(0, members.get(0).getContributedVal());
    }

    @Test
    @DisplayName("명단은 요청이 아니라 파티에서 온다 — 요청자가 보낸 명단으로 인가를 검사할 수 없다")
    void 명단은_파티에서_온다() {
        service.create(validCommand());

        assertEquals(List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D),
                savedMembers().stream().map(PartyQuestMember::getUserId).toList());
    }

    @Test
    @DisplayName("지표도 파티에서 온다 — 파티장이 다시 고를 수 없다")
    void 지표는_파티에서_온다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ACTIVE, PartyMetric.ACTIVE_MINUTES, OWNER,
                        List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D)));

        service.create(validCommand());

        assertEquals(Metric.ACTIVE_MINUTES, savedQuest().getMetric());
    }

    @Test
    @DisplayName("한 번도 동기화한 적 없는 멤버는 기준값을 비워둔다 — 0으로 두면 공짜 완료가 난다")
    void 스냅샷이_없으면_기준값을_비워둔다() {
        service.create(validCommand());

        // MEMBER_D 는 스냅샷이 없다
        assertNull(savedMembers().get(3).getBaselineVal());
    }

    @Test
    @DisplayName("최신 스냅샷이 어제 것이면 기준값을 비워둔다 — 어제 누적값을 쓰면 오늘 기여가 계속 음수라 완료되지 않는다")
    void 어제_스냅샷이면_기준값을_비워둔다() {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        when(snapshotRepository.findAllByUserIds(any())).thenReturn(List.of(
                snapshotOf(OWNER, 3000),
                // 어제 밤 마지막 동기화 뒤로 오늘은 아직 한 번도 동기화하지 않았다
                UserLatestSnapshot.create(MEMBER_B, new Snapshot(
                        yesterday, yesterday.atTime(23, 30).atZone(KST).toInstant(), 8000, 0, 0))));

        PartyQuestCreation creation = service.create(validCommand());

        // 그 멤버 때문에 파티 전체의 생성을 막지 않는다
        assertInstanceOf(PartyQuestCreation.Created.class, creation);
        List<PartyQuestMember> members = savedMembers();
        assertEquals(3000, members.get(0).getBaselineVal());
        assertNull(members.get(1).getBaselineVal());
    }

    @Test
    @DisplayName("기한은 오늘 한국 시간 자정 직전이다 — 누적값이 자정에 0으로 돌아가기 때문이다")
    void 기한은_오늘_자정_직전이다() {
        service.create(validCommand());

        PartyQuest quest = savedQuest();
        Instant expected = Instant.now().atZone(KST).toLocalDate()
                .atTime(LocalTime.of(23, 59, 59)).atZone(KST).toInstant();
        assertEquals(expected, quest.getExpiredAt());
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
    }

    @Test
    @DisplayName("보상은 요청에 없다 — 정책이 정한다")
    void 보상은_정책이_정한다() {
        service.create(validCommand());

        assertEquals(new RewardPolicy().partyQuestRewardXp(), savedQuest().getRewardXp());
    }

    @Test
    @DisplayName("소속된 파티가 없으면 파티 쪽 예외가 그대로 올라간다 — 잡으면 롤백 전용 플래그가 남는다")
    void 파티가_없으면_예외가_그대로_올라간다() {
        when(partyQueryService.getMyParty(OWNER))
                .thenThrow(new PartyException(PartyErrorCode.NOT_IN_PARTY));

        assertThrows(PartyException.class, () -> service.create(validCommand()));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("남의 파티 아이디를 보내면 만들지 않는다 — 자기 파티가 아니면 거기서 끝난다")
    void 남의_파티는_만들지_않는다() {
        PartyQuestCreateCommand others =
                new PartyQuestCreateCommand(UUID.randomUUID(), OWNER, "퇴근길 함께 4000보", 4000);

        assertEquals(PartyQuestCreation.Reason.NOT_A_MEMBER, reasonOf(service.create(others)));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("파티원이지만 파티장이 아니면 만들지 않는다")
    void 파티장이_아니면_만들지_않는다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ACTIVE, PartyMetric.STEPS, MEMBER_B,
                        List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D)));

        assertEquals(PartyQuestCreation.Reason.NOT_OWNER, reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("모집 중이면 만들지 않는다 — 뒤에 들어온 멤버가 명단에 없는 채로 남는다")
    void 모집_중이면_만들지_않는다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.RECRUITING, PartyMetric.STEPS, OWNER,
                        List.of(OWNER, MEMBER_B)));

        assertEquals(PartyQuestCreation.Reason.PARTY_NOT_ACTIVE, reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 끝난 파티에도 만들지 않는다")
    void 끝난_파티에는_만들지_않는다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ENDED, PartyMetric.STEPS, OWNER,
                        List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D)));

        assertEquals(PartyQuestCreation.Reason.PARTY_NOT_ACTIVE, reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("파티 인원이 정원을 넘으면 만들지 않는다 — 멤버 행이 다섯이면 XP 가 다섯 번 나간다")
    void 정원을_넘으면_만들지_않는다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ACTIVE, PartyMetric.STEPS, OWNER,
                        List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D, UUID.randomUUID())));

        assertEquals(PartyQuestCreation.Reason.INVALID_MEMBERS, reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 사람이 두 번 들어 있으면 만들지 않는다 — 멤버 행 유니크 제약보다 먼저 답한다")
    void 중복_멤버면_만들지_않는다() {
        when(partyQueryService.getMyParty(OWNER)).thenReturn(
                party(PartyStatus.ACTIVE, PartyMetric.STEPS, OWNER,
                        List.of(OWNER, MEMBER_B, MEMBER_B)));

        assertEquals(PartyQuestCreation.Reason.INVALID_MEMBERS, reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("목표가 0 이하면 만들지 않는다 — 첫 스냅샷에서 바로 완료되어 XP가 공짜로 나간다")
    void 목표가_0_이하면_만들지_않는다() {
        assertEquals(PartyQuestCreation.Reason.INVALID_TARGET,
                reasonOf(service.create(command("퇴근길 함께", 0))));
        assertEquals(PartyQuestCreation.Reason.INVALID_TARGET,
                reasonOf(service.create(command("퇴근길 함께", -1))));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("제목이 비었거나 100자를 넘으면 만들지 않는다 — 사람이 입력한 값이라 자르지 않고 거절한다")
    void 제목이_비었거나_길면_만들지_않는다() {
        assertEquals(PartyQuestCreation.Reason.INVALID_TITLE,
                reasonOf(service.create(command("   ", 4000))));
        assertEquals(PartyQuestCreation.Reason.INVALID_TITLE,
                reasonOf(service.create(command("가".repeat(101), 4000))));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 진행 중인 파티 퀘스트가 있으면 만들지 않는다")
    void 이미_진행_중이면_막는다() {
        when(partyQuestRepository.existsActiveByPartyId(any(), any())).thenReturn(true);

        assertEquals(PartyQuestCreation.Reason.PARTY_QUEST_ALREADY_ACTIVE,
                reasonOf(service.create(validCommand())));

        verify(partyQuestRepository, never()).save(any());
    }

    @Test
    @DisplayName("생성되면 알림용 이벤트를 한 건 적재한다 — 파티장 외 멤버는 이것 말고 시작을 알 방법이 없다")
    void 생성_이벤트를_적재한다() {
        ArgumentCaptor<PartyQuestCreatedData> data = ArgumentCaptor.forClass(PartyQuestCreatedData.class);

        service.create(validCommand());

        PartyQuest quest = savedQuest();
        verify(outboxRecorder).append(
                eq(AggregateType.PARTY_QUEST),
                eq(quest.getPartyQuestId()),
                eq(OutboxEventType.PARTY_QUEST_CREATED),
                // 봉투의 userId 가 발행 파티션을 정한다. 파티장이다.
                eq(OWNER),
                any(Instant.class),
                data.capture());

        PartyQuestCreatedData payload = data.getValue();
        assertEquals(PARTY_ID, payload.partyId());
        assertEquals("퇴근길 함께 4000보", payload.title());
        assertEquals(Metric.STEPS.name(), payload.metric());
        assertEquals(4000, payload.targetValue());
        assertEquals(quest.getExpiredAt(), payload.expiredAt());
    }

    @Test
    @DisplayName("명단에는 파티장도 들어가고 ownerId 로 구분한다 — 제외 여부는 알림 쪽이 정한다")
    void 명단에_파티장이_들어가고_ownerId로_구분한다() {
        service.create(validCommand());

        PartyQuestCreatedData payload = recordedPayload();
        assertEquals(OWNER, payload.ownerId());
        assertEquals(List.of(OWNER, MEMBER_B, MEMBER_C, MEMBER_D),
                payload.members().stream().map(PartyQuestCreatedData.Member::userId).toList());
    }

    @Test
    @DisplayName("만들지 못하면 이벤트도 적재하지 않는다 — 없는 퀘스트의 시작 알림이 나가면 안 된다")
    void 실패하면_적재하지_않는다() {
        when(partyQuestRepository.existsActiveByPartyId(any(), any())).thenReturn(true);

        assertEquals(PartyQuestCreation.Reason.PARTY_QUEST_ALREADY_ACTIVE,
                reasonOf(service.create(validCommand())));

        verify(outboxRecorder, never()).append(any(), any(), any(), any(), any(), any());
    }
}
