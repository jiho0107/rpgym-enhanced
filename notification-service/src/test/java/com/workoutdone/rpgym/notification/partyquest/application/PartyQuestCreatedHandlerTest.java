package com.workoutdone.rpgym.notification.partyquest.application;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
import com.workoutdone.rpgym.notification.partyquest.adapter.out.slack.PartyQuestSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;
import com.workoutdone.rpgym.notification.slack.SlackMessageSendException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyQuestCreatedHandlerTest {

    private static final UUID PARTY_QUEST_ID = UUID.fromString("3d9a1c47-5e02-4b8f-9a6d-7c1e2f084b55");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEMBER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER_C = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final String SLACK_OWNER = "U_OWNER";
    private static final String SLACK_B = "U_MEMBER_B";
    private static final String SLACK_C = "U_MEMBER_C";

    private static final SlackMessageResult OK = new SlackMessageResult("D0123456789", "1732147200.000100");

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private PartyQuestSlackNotifier partyQuestSlackNotifier;

    private PartyQuestCreatedHandler handler;

    @BeforeEach
    void setUp() {
        // 기본 RetryTemplate = 최대 3회 시도, 대기 없음 (운영 RetryConfig 와 시도 횟수가 같다)
        handler = new PartyQuestCreatedHandler(userServiceClient, partyQuestSlackNotifier, new RetryTemplate());
    }

    private PartyQuestCreatedData data(UUID... memberIds) {
        List<PartyQuestCreatedData.Member> members = Arrays.stream(memberIds)
                .map(PartyQuestCreatedData.Member::new)
                .toList();
        return new PartyQuestCreatedData(
                PARTY_QUEST_ID, UUID.randomUUID(), OWNER_ID,
                "오늘 다 같이 만보 걷기", "STEPS", 40000, 100,
                Instant.parse("2026-09-24T14:59:59Z"), members);
    }

    private UserInfoResponse user(UUID id, String slackId) {
        return new UserInfoResponse(id, "닉네임", "USER", "ACTIVE", slackId);
    }

    @Test
    @DisplayName("파티장을 포함한 멤버 전원에게 각자의 slackId 로 발송한다")
    void sendsToAllMembersIncludingOwner() {
        when(userServiceClient.getUserInfo(OWNER_ID)).thenReturn(user(OWNER_ID, SLACK_OWNER));
        when(userServiceClient.getUserInfo(MEMBER_B)).thenReturn(user(MEMBER_B, SLACK_B));
        when(userServiceClient.getUserInfo(MEMBER_C)).thenReturn(user(MEMBER_C, SLACK_C));
        when(partyQuestSlackNotifier.sendCreated(any(), any())).thenReturn(OK);

        PartyQuestCreatedData data = data(OWNER_ID, MEMBER_B, MEMBER_C);
        handler.handle(data);

        verify(partyQuestSlackNotifier).sendCreated(SLACK_OWNER, data);
        verify(partyQuestSlackNotifier).sendCreated(SLACK_B, data);
        verify(partyQuestSlackNotifier).sendCreated(SLACK_C, data);
        verify(partyQuestSlackNotifier, times(3)).sendCreated(any(), any());
    }

    @Test
    @DisplayName("slackId 가 null 이거나 비어 있는 멤버는 건너뛰고 나머지 멤버에게는 정상 발송한다")
    void skipsMembersWithoutSlackId() {
        UUID noSlack = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID blankSlack = UUID.fromString("55555555-5555-4555-8555-555555555555");
        when(userServiceClient.getUserInfo(noSlack)).thenReturn(user(noSlack, null));
        when(userServiceClient.getUserInfo(blankSlack)).thenReturn(user(blankSlack, "  "));
        when(userServiceClient.getUserInfo(OWNER_ID)).thenReturn(user(OWNER_ID, SLACK_OWNER));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_OWNER), any())).thenReturn(OK);

        handler.handle(data(noSlack, blankSlack, OWNER_ID));

        verify(partyQuestSlackNotifier, times(1)).sendCreated(any(), any());
        verify(partyQuestSlackNotifier).sendCreated(eq(SLACK_OWNER), any());
    }

    @Test
    @DisplayName("한 멤버의 Slack 발송이 계속 실패해도 예외가 밖으로 나가지 않고 다른 멤버에게는 발송된다")
    void oneMembersPersistentFailureDoesNotBlockOthers() {
        when(userServiceClient.getUserInfo(OWNER_ID)).thenReturn(user(OWNER_ID, SLACK_OWNER));
        when(userServiceClient.getUserInfo(MEMBER_B)).thenReturn(user(MEMBER_B, SLACK_B));
        when(userServiceClient.getUserInfo(MEMBER_C)).thenReturn(user(MEMBER_C, SLACK_C));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_OWNER), any())).thenReturn(OK);
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_B), any()))
                .thenThrow(new SlackMessageSendException("Slack 발송 실패. error=ratelimited"));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_C), any())).thenReturn(OK);

        // 예외가 밖으로 나가면 Kafka 가 메시지 전체를 재전송해 이미 받은 멤버가 중복 DM 을 받는다.
        assertThatCode(() -> handler.handle(data(OWNER_ID, MEMBER_B, MEMBER_C))).doesNotThrowAnyException();

        verify(partyQuestSlackNotifier).sendCreated(eq(SLACK_OWNER), any());
        verify(partyQuestSlackNotifier).sendCreated(eq(SLACK_C), any());
        // 실패한 멤버는 RetryTemplate 의 최대 시도 횟수(3회)만큼 시도한다.
        verify(partyQuestSlackNotifier, times(3)).sendCreated(eq(SLACK_B), any());
    }

    @Test
    @DisplayName("일시적인 Slack 실패는 재시도로 복구되어 그 멤버도 발송된다 (중복 발송 없음)")
    void transientSlackFailureIsRecoveredByRetry() {
        when(userServiceClient.getUserInfo(OWNER_ID)).thenReturn(user(OWNER_ID, SLACK_OWNER));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_OWNER), any()))
                .thenThrow(new SlackMessageSendException("Slack API 호출에 실패했다."))
                .thenReturn(OK);

        assertThatCode(() -> handler.handle(data(OWNER_ID))).doesNotThrowAnyException();

        // 1회 실패 + 1회 성공 = 2회 호출. 성공한 뒤에는 더 부르지 않는다.
        verify(partyQuestSlackNotifier, times(2)).sendCreated(eq(SLACK_OWNER), any());
    }

    @Test
    @DisplayName("한 멤버의 user-service 조회가 실패해도 그 멤버만 건너뛰고 다음 멤버는 발송한다")
    void userServiceFailureSkipsOnlyThatMember() {
        when(userServiceClient.getUserInfo(OWNER_ID)).thenThrow(new IllegalStateException("user-service down"));
        when(userServiceClient.getUserInfo(MEMBER_B)).thenReturn(user(MEMBER_B, SLACK_B));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_B), any())).thenReturn(OK);

        assertThatCode(() -> handler.handle(data(OWNER_ID, MEMBER_B))).doesNotThrowAnyException();

        verify(partyQuestSlackNotifier, never()).sendCreated(eq(SLACK_OWNER), any());
        verify(partyQuestSlackNotifier).sendCreated(eq(SLACK_B), any());
    }

    @Test
    @DisplayName("멤버 userId 가 null 이면 그 항목만 건너뛰고 나머지는 발송한다")
    void skipsMemberWithNullUserId() {
        when(userServiceClient.getUserInfo(MEMBER_B)).thenReturn(user(MEMBER_B, SLACK_B));
        when(partyQuestSlackNotifier.sendCreated(eq(SLACK_B), any())).thenReturn(OK);

        handler.handle(data(null, MEMBER_B));

        verify(userServiceClient, times(1)).getUserInfo(any());
        verify(partyQuestSlackNotifier, times(1)).sendCreated(any(), any());
    }

    @Test
    @DisplayName("필수 필드(data, partyQuestId, title, members)가 없으면 아무것도 호출하지 않고 정상 종료한다")
    void skipsWhenRequiredFieldsAreMissing() {
        Instant expiredAt = Instant.parse("2026-09-24T14:59:59Z");
        List<PartyQuestCreatedData.Member> oneMember = List.of(new PartyQuestCreatedData.Member(OWNER_ID));

        assertThatCode(() -> {
            handler.handle(null);
            handler.handle(new PartyQuestCreatedData(
                    null, UUID.randomUUID(), OWNER_ID, "제목", "STEPS", 1, 1, expiredAt, oneMember));
            handler.handle(new PartyQuestCreatedData(
                    PARTY_QUEST_ID, UUID.randomUUID(), OWNER_ID, null, "STEPS", 1, 1, expiredAt, oneMember));
            handler.handle(new PartyQuestCreatedData(
                    PARTY_QUEST_ID, UUID.randomUUID(), OWNER_ID, "제목", "STEPS", 1, 1, expiredAt, null));
            handler.handle(new PartyQuestCreatedData(
                    PARTY_QUEST_ID, UUID.randomUUID(), OWNER_ID, "제목", "STEPS", 1, 1, expiredAt, List.of()));
        }).doesNotThrowAnyException();

        verifyNoInteractions(userServiceClient, partyQuestSlackNotifier);
    }
}
