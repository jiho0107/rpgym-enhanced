package com.workoutdone.rpgym.game.quest.application;

import com.workoutdone.rpgym.game.outbox.application.OutboxRecorder;
import com.workoutdone.rpgym.game.outbox.domain.AggregateType;
import com.workoutdone.rpgym.game.outbox.domain.OutboxEventType;
import com.workoutdone.rpgym.game.party.application.PartyQueryService;
import com.workoutdone.rpgym.game.party.application.view.PartyView;
import com.workoutdone.rpgym.game.party.domain.PartyStatus;
import com.workoutdone.rpgym.game.quest.application.payload.PartyQuestCreatedData;
import com.workoutdone.rpgym.game.quest.domain.Metric;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuest;
import com.workoutdone.rpgym.game.quest.domain.aggregate.PartyQuestMember;
import com.workoutdone.rpgym.game.quest.domain.aggregate.UserLatestSnapshot;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestMemberRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.PartyQuestRepository;
import com.workoutdone.rpgym.game.quest.domain.repo.UserLatestSnapshotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 파티장이 파티 퀘스트를 만든다.
// 파티가 만들어질 때 자동으로 생기지 않는다. 파티 담당자와 그렇게 합의했다.
// 파티는 그냥 사람이 모인 것이고, 무엇을 얼마나 할지는 파티장이 따로 정하는 일이라
// 목표값과 지표를 누가 정하느냐는 문제가 생기지 않는 편이 낫다.
// 그래서 파티 쪽은 나에게 아무것도 보내지 않고, 파티장이 이 API 를 부른다.
//
// 명단과 지표를 요청에서 받지 않고 파티에서 읽는 이유가 이 클래스의 핵심이다.
// 요청자가 보낸 명단으로 "요청자가 그 명단에 있는가" 를 검사하면
// 요청자가 준 데이터를 요청자가 준 데이터로 검사하는 것이라 인가가 성립하지 않는다.
// 남의 파티 아이디와 자기 아이디가 든 아무 명단이나 보내면 그대로 통과한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyQuestCreateService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);
    private static final int MAX_MEMBERS = 4;
    private static final int MAX_TITLE_LENGTH = 100;

    private final PartyQuestRepository partyQuestRepository;
    private final PartyQuestMemberRepository partyQuestMemberRepository;
    private final UserLatestSnapshotRepository userLatestSnapshotRepository;
    private final PartyQueryService partyQueryService;
    private final RewardPolicy rewardPolicy;
    private final OutboxRecorder outboxRecorder;

    @Transactional
    public PartyQuestCreation create(PartyQuestCreateCommand command) {
        // 사람이 지금 요청한 것이므로 서버 시계를 쓴다.
        // 이벤트를 처리할 때 측정 시각을 쓰는 것과 기준 시각의 성격이 다르다.
        Instant now = Instant.now();

        // 인가가 먼저다. 입력값 검사를 앞에 두면 다른 파티에 대조할때 어떤 값이 올바른지를 알아낼 수 있다.
        // 파티 컨텍스트와는 application 의 서비스로만 붙는다. 다른 당담자의 리포지토리를 직접 주입하지 않는다.
        // 다른쪽 레포를 건드려도 되는데 내가 만들어야하므로 그냥 안했다.
        // 소속된 파티가 없으면 이 호출이 예외를 던진다. 잡지 않는다.
        // 저 메서드는 전파가 REQUIRED 라 내 트랜잭션에 참여만 하고, 거기서 예외는
        // 다른(다른사람이 짠) 트랜잭션이라 스스로 롤백하지 못하고 롤백 전용 플래그만 세운다.
        // 여기서 잡아 실패 결과로 돌려주면 플래그는 그대로 남아 커밋 시점에 UnexpectedRollbackException 이 난다.
        // 그대로 올려보내면 공용 예외 핸들러가 파티 쪽 에러코드로 응답한다. 쓴 것이 없으니 롤백도 무해하다.
        PartyView party = partyQueryService.getMyParty(command.requesterId());
        if (!party.partyId().equals(command.partyId())) {
            return failed(PartyQuestCreation.Reason.NOT_A_MEMBER, command);
        }
        if (!party.ownerId().equals(command.requesterId())) {
            return failed(PartyQuestCreation.Reason.NOT_OWNER, command);
        }
        // 모집 중이면 멤버가 더 들어올 수 있는데, 명단은 생성 시점에 복사하고 그 뒤로 읽지 않는다.
        // 끝났거나 해산한 파티도 같은 자리에서 막는다.
        if (party.status() != PartyStatus.ACTIVE) {
            return failed(PartyQuestCreation.Reason.PARTY_NOT_ACTIVE, command);
        }

        List<UUID> memberIds = party.members().stream()
                .map(PartyView.MemberView::userId)
                .toList();
        // 파티가 정원을 지키므로 평소에 걸리지 않는다. 그래도 남겨둔다.
        // 여기가 뚫리면 멤버 행이 다섯 개 생기고 완료 시 XP 가 다섯 번 나간다.
        if (memberIds.isEmpty() || memberIds.size() > MAX_MEMBERS) {
            return failed(PartyQuestCreation.Reason.INVALID_MEMBERS, command);
        }
        // 같은 사람이 두 번 들어오면 멤버 행의 유니크 제약에 걸린다.
        // 그 예외를 그대로 두면 500 이 나가므로 여기서 먼저 답한다.
        if (new HashSet<>(memberIds).size() != memberIds.size()) {
            return failed(PartyQuestCreation.Reason.INVALID_MEMBERS, command);
        }

        String title = command.title();
        if (title == null || title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
            return failed(PartyQuestCreation.Reason.INVALID_TITLE, command);
        }

        // 지표는 파티가 이미 확정해서 들고 있다. 파티장이 다시 고를 수 없다.
        // 두 enum 의 값 이름이 같다는 것이 파티 담당자와의 계약이라 변환은 실패할 수 없다.
        // 실패한다면 한쪽에만 값을 추가한 것이므로 사용자가 고칠 수 있는 종류가 아니다.
        Metric metric = Metric.from(party.metric().name())
                .orElseThrow(() -> {
                    log.error("파티의 지표를 퀘스트 지표로 바꾸지 못했다. 두 enum 이 어긋났다. partyId={} partyMetric={}",
                            party.partyId(), party.metric());
                    return new IllegalStateException("파티 지표를 퀘스트 지표로 바꿀 수 없다: " + party.metric());
                });

        if (command.targetValue() <= 0) {
            return failed(PartyQuestCreation.Reason.INVALID_TARGET, command);
        }

        // 파티당 활성 퀘스트는 하나다.
        // 여러 개를 허용하면 같은 걸음이 여러 퀘스트에 동시에 들어가고,
        // 유저가 자기 활동이 어디에 얼마나 반영됐는지 알 수 없게 된다.
        if (partyQuestRepository.existsActiveByPartyId(command.partyId(), now)) {
            return failed(PartyQuestCreation.Reason.PARTY_QUEST_ALREADY_ACTIVE, command);
        }

        // 기한은 오늘 한국 시간 자정 직전이다.
        // 하루를 넘길 수 없는 이유는 건강 데이터가 자정마다 0 으로 돌아가는 당일 누적값이고,
        // 어제 누적값을 보관하는 테이블이 어디에도 없기 때문이다.
        // 며칠짜리로 만들면 날짜별 증분을 따로 쌓아야 하고, 그러면 이벤트가 하루치 통째로 유실될 때
        // 복구할 방법이 없어진다.
        Instant expiredAt = endOfDayKst(now);
        if (!expiredAt.isAfter(now)) {
            // 자정 직전 1 초 안에 요청이 들어온 경우다. 만들어도 바로 만료된다.
            return failed(PartyQuestCreation.Reason.TOO_LATE_IN_DAY, command);
        }

        PartyQuest partyQuest = partyQuestRepository.save(PartyQuest.create(
                UUID.randomUUID(),
                command.partyId(),
                title,
                metric,
                command.targetValue(),
                rewardPolicy.partyQuestRewardXp(),
                now,
                expiredAt
        ));

        List<PartyQuestMember> members = partyQuestMemberRepository.saveAll(
                enroll(partyQuest.getPartyQuestId(), memberIds, metric, now.atZone(KST).toLocalDate()));

        // 파티장은 이 요청의 응답으로 결과를 받지만 나머지 멤버는 시작을 알 방법이 없다.
        // 봉투의 userId 로 파티장을 쓴다. 이 값이 발행 파티션을 정하는데,
        // 생성 직후에는 기여가 전원 0 이라 완료 이벤트와 순서가 엇갈릴 상황이 없다.
        // 누구에게 알릴지는 본문의 명단이 들고 있다.
        outboxRecorder.append(
                AggregateType.PARTY_QUEST,
                partyQuest.getPartyQuestId(),
                OutboxEventType.PARTY_QUEST_CREATED,
                command.requesterId(),
                now,
                PartyQuestCreatedData.from(partyQuest, command.requesterId(), members)
        );

        log.info("파티 퀘스트 생성. partyQuestId={} partyId={} metric={} target={} 멤버={}명 기한={}",
                partyQuest.getPartyQuestId(), partyQuest.getPartyId(), partyQuest.getMetric(),
                partyQuest.getTargetVal(), members.size(), partyQuest.getExpiredAt());

        return new PartyQuestCreation.Created(PartyQuestView.from(partyQuest, members));
    }

    // 멤버 명단을 만들면서 각자의 기준값을 채운다.
    // 기준값은 내가 이미 들고 있는 유저별 최신 스냅샷에서 가져온다.
    // Health 에 물어보지 않는다. 물어보면 그쪽이 죽었을 때 퀘스트를 못 만든다.
    // 한 번도 동기화한 적 없는 유저는 스냅샷이 없어서 비워둔다.
    // 그 멤버 하나 때문에 파티 전체의 퀘스트 생성을 막는 것은 과하다.
    // 비워두면 그 멤버의 첫 이벤트가 도착할 때 확정된다.
    private List<PartyQuestMember> enroll(UUID partyQuestId, List<UUID> memberIds, Metric metric, LocalDate today) {
        Map<UUID, UserLatestSnapshot> snapshots = userLatestSnapshotRepository
                .findAllByUserIds(memberIds).stream()
                .collect(Collectors.toMap(UserLatestSnapshot::getUserId, Function.identity()));

        return memberIds.stream()
                .map(userId -> PartyQuestMember.join(
                        UUID.randomUUID(),
                        partyQuestId,
                        userId,
                        baselineOf(snapshots.get(userId), metric, today)))
                .toList();
    }

    // 스냅샷이 없으면 0 이 아니라 비어 있는 값을 준다.
    // 0 으로 두면 그 유저가 오늘 이미 걸어둔 활동이 통째로 기여로 잡혀서,
    // 목표가 작으면 퀘스트가 시작하자마자 완료되고 XP 가 공짜로 나간다.
    //
    // 스냅샷이 있어도 오늘 것이 아니면 없는 것과 같이 다룬다.
    // 최신 스냅샷은 오늘 아직 동기화하지 않은 유저라면 어제 밤 값이다.
    // 어제 누적값을 기준으로 잡으면 오늘 누적값은 0 부터 다시 오르므로
    // 그 값을 넘을 때까지 기여가 음수로 무시되고, 하루짜리 퀘스트라 대개 끝까지 완료되지 않는다.
    // 개인 퀘스트 수락의 DATE_MISMATCH 와 같은 이유인데, 여기서는 거절하지 않는다.
    // 멤버 한 사람의 스냅샷이 낡았다는 이유로 파티 전체의 생성을 막는 것은 과하다.
    private static Integer baselineOf(UserLatestSnapshot snapshot, Metric metric, LocalDate today) {
        if (snapshot == null || !snapshot.getActivityDate().equals(today)) {
            return null;
        }
        return snapshot.toSnapshot().valueOf(metric);
    }

    private static Instant endOfDayKst(Instant now) {
        return now.atZone(KST).toLocalDate().atTime(END_OF_DAY).atZone(KST).toInstant();
    }

    private PartyQuestCreation failed(PartyQuestCreation.Reason reason, PartyQuestCreateCommand command) {
        log.info("파티 퀘스트 생성 실패. reason={} partyId={} requesterId={}",
                reason, command.partyId(), command.requesterId());
        return new PartyQuestCreation.Failed(reason);
    }
}
