package com.workoutdone.rpgym.game.achievement.application;

import com.workoutdone.rpgym.game.achievement.domain.AchievementScope;
import com.workoutdone.rpgym.game.achievement.domain.ConditionType;
import com.workoutdone.rpgym.game.achievement.domain.OwnerType;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.Achievement;
import com.workoutdone.rpgym.game.achievement.domain.aggregate.UserAchievement;
import com.workoutdone.rpgym.game.achievement.domain.repo.AchievementRepository;
import com.workoutdone.rpgym.game.achievement.domain.repo.UserAchievementRepository;
import com.workoutdone.rpgym.game.party.domain.repo.PartyMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * "파티 하나 종료 → 완주자마다 원본에서 완주 수를 다시 읽어 덮어쓴다" 흐름.
 * 세지 않고 읽는다는 게 핵심이라, 리포지토리가 돌려주는 절대값이 그대로 current_value 가 되는지 본다.
 */
class PartyAchievementServiceTest {

    private static final UUID PARTY = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final Instant ENDED_AT = Instant.parse("2026-09-22T00:00:00Z");

    private AchievementRepository achievementRepository;
    private UserAchievementRepository userAchievementRepository;
    private PartyMemberRepository partyMemberRepository;
    private PartyAchievementService sut;

    private Achievement first;   // PARTY_COMPLETED_COUNT 1
    private Achievement three;   // PARTY_COMPLETED_COUNT 3

    @BeforeEach
    void setUp() {
        achievementRepository = mock(AchievementRepository.class);
        userAchievementRepository = mock(UserAchievementRepository.class);
        partyMemberRepository = mock(PartyMemberRepository.class);
        sut = new PartyAchievementService(achievementRepository, userAchievementRepository, partyMemberRepository);

        first = Achievement.create(UUID.randomUUID(), "PARTY_COMPLETED_FIRST", "첫 파티 완주", "파티를 처음으로 끝까지 함께함",
                AchievementScope.PARTY, ConditionType.PARTY_COMPLETED_COUNT, 1, 0);
        three = Achievement.create(UUID.randomUUID(), "PARTY_COMPLETED_3", "파티 3회 완주", "파티를 3번 끝까지 함께함",
                AchievementScope.PARTY, ConditionType.PARTY_COMPLETED_COUNT, 3, 0);

        given(achievementRepository.findActiveByConditionTypes(ConditionType.PARTY)).willReturn(List.of(first, three));
        given(userAchievementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(userAchievementRepository.findAllByOwner(any(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("첫 완주 — 완주자 2명 모두 FIRST 를 따고, 3회는 1/3 로 진행된다")
    void firstPartyUnlocksFirstForEveryCompleter() {
        given(partyMemberRepository.findUserIdsCompletedAt(PARTY, ENDED_AT)).willReturn(List.of(ALICE, BOB));
        given(partyMemberRepository.countCompletedByUserId(ALICE)).willReturn(1L);
        given(partyMemberRepository.countCompletedByUserId(BOB)).willReturn(1L);

        Map<UUID, List<Achievement>> unlocked = sut.recordPartyCompleted(PARTY, ENDED_AT);

        assertThat(unlocked).containsOnlyKeys(ALICE, BOB);
        assertThat(unlocked.get(ALICE)).containsExactly(first);
        assertThat(unlocked.get(BOB)).containsExactly(first);

        ArgumentCaptor<UserAchievement> saved = ArgumentCaptor.forClass(UserAchievement.class);
        verify(userAchievementRepository, times(4)).save(saved.capture());   // 2명 × 2업적
        assertThat(saved.getAllValues()).allSatisfy(ua -> {
            assertThat(ua.getCurrentValue()).isEqualTo(1);
            assertThat(ua.getOwnerType()).isEqualTo(OwnerType.USER);
        });
    }

    @Test
    @DisplayName("3번째 완주 — 원본이 3을 돌려주면 FIRST 는 이미 ACHIEVED 라 건너뛰고 3회가 ACHIEVED")
    void thirdPartyUnlocksThree() {
        UserAchievement firstRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, ALICE, first.getId());
        firstRow.applyAbsolute(1, 1, ENDED_AT.minusSeconds(1));
        UserAchievement threeRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, ALICE, three.getId());
        threeRow.applyAbsolute(2, 3, ENDED_AT.minusSeconds(1));
        given(userAchievementRepository.findAllByOwner(OwnerType.USER, ALICE)).willReturn(List.of(firstRow, threeRow));
        given(partyMemberRepository.findUserIdsCompletedAt(PARTY, ENDED_AT)).willReturn(List.of(ALICE));
        given(partyMemberRepository.countCompletedByUserId(ALICE)).willReturn(3L);

        Map<UUID, List<Achievement>> unlocked = sut.recordPartyCompleted(PARTY, ENDED_AT);

        assertThat(unlocked.get(ALICE)).containsExactly(three);
        verify(userAchievementRepository, times(1)).save(threeRow);
        verify(userAchievementRepository, never()).save(firstRow);
        assertThat(threeRow.getCurrentValue()).isEqualTo(3);
        assertThat(threeRow.getAchievedAt()).isEqualTo(ENDED_AT);
    }

    @Test
    @DisplayName("같은 이벤트 재전송 — 원본이 같은 값을 돌려주니 저장이 없다 (중복 방지 테이블 없이 멱등)")
    void redeliveryIsNoOp() {
        UserAchievement firstRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, ALICE, first.getId());
        firstRow.applyAbsolute(1, 1, ENDED_AT);
        UserAchievement threeRow = UserAchievement.start(UUID.randomUUID(), OwnerType.USER, ALICE, three.getId());
        threeRow.applyAbsolute(1, 3, ENDED_AT);
        given(userAchievementRepository.findAllByOwner(OwnerType.USER, ALICE)).willReturn(List.of(firstRow, threeRow));
        given(partyMemberRepository.findUserIdsCompletedAt(PARTY, ENDED_AT)).willReturn(List.of(ALICE));
        given(partyMemberRepository.countCompletedByUserId(ALICE)).willReturn(1L);

        Map<UUID, List<Achievement>> unlocked = sut.recordPartyCompleted(PARTY, ENDED_AT);

        assertThat(unlocked).isEmpty();
        verify(userAchievementRepository, never()).save(any());
    }

    @Test
    @DisplayName("완주자가 없으면(중간 탈퇴만 있던 파티) 아무것도 하지 않는다")
    void noCompleters() {
        given(partyMemberRepository.findUserIdsCompletedAt(PARTY, ENDED_AT)).willReturn(List.of());

        Map<UUID, List<Achievement>> unlocked = sut.recordPartyCompleted(PARTY, ENDED_AT);

        assertThat(unlocked).isEmpty();
        verify(userAchievementRepository, never()).save(any());
        verify(partyMemberRepository, never()).countCompletedByUserId(any());
    }
}
