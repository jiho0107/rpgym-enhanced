package com.workoutdone.rpgym.notification.partyquest.application;

import com.workoutdone.rpgym.notification.partyquest.adapter.in.kafka.dto.PartyQuestCreatedData;
import com.workoutdone.rpgym.notification.partyquest.adapter.out.slack.PartyQuestSlackNotifier;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.UserServiceClient;
import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;
import com.workoutdone.rpgym.notification.slack.SlackMessageResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * PARTY_QUEST_CREATED 를 받아 파티장을 포함한 파티원 전원에게 Slack DM을 보낸다.
 *
 * 이 클래스는 트랜잭션을 열지 않는다 -- DB를 쓰지 않고, Slack 호출은 외부 I/O다.
 *
 * 멤버 한 명의 실패가 다른 멤버의 발송을 막지 않게, 멤버별 예외를 여기서 삼키고 로그만 남긴다.
 * 예외를 밖으로 던지면 Kafka가 메시지 전체를 재전송해서 이미 받은 멤버가 같은 DM을 또 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartyQuestCreatedHandler {

    private final UserServiceClient userServiceClient;
    private final PartyQuestSlackNotifier partyQuestSlackNotifier;
    private final RetryTemplate retryTemplate;

    public void handle(PartyQuestCreatedData data) {
        if (data == null || data.partyQuestId() == null || data.title() == null
                || data.members() == null || data.members().isEmpty()) {
            // 재시도해도 같은 데이터가 같은 곳에서 깨진다.
            log.error("PARTY_QUEST_CREATED 필수 필드 누락. 건너뛴다. data={}", data);
            return;
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;

        // TODO: 가장 안전한 방식은 (partyQuestId, userId) 단위 발송 이력 테이블을 두는 것이다.
        //   - 멤버 전원을 PENDING 으로 적재 -> 발송 성공 시 SENT, slackId 없으면 SKIPPED, 일시 장애는 FAILED
        //   - retryable 실패가 있으면 예외를 던져 Kafka 재전송 -> SENT/SKIPPED 는 걸러져 실패한 멤버만 재발송
        //   - 지금은 시간 관계상 생략한다:
        //     Slack 호출은 RetryTemplate 로 3회까지 재시도하지만, 이력 테이블이 없어서
        //     Slack 이 수 초 이상 죽어 있으면 실패한 멤버는 재발송 없이 유실되고,
        //     발송 도중 프로세스가 죽어 Kafka 가 재전송하면 앞선 멤버가 중복 DM 을 받을 수 있다.
        for (PartyQuestCreatedData.Member member : data.members()) {
            UUID userId = member == null ? null : member.userId();
            if (userId == null) {
                log.error("멤버 userId 누락. 건너뛴다. partyQuestId={}", data.partyQuestId());
                skipped++;
                continue;
            }

            try {
                UserInfoResponse userInfo = userServiceClient.getUserInfo(userId);
                if (userInfo.slackId() == null || userInfo.slackId().isBlank()) {
                    // 재시도해도 slackId가 저절로 생기지 않는다.
                    log.warn("slackId가 없어 발송하지 않는다. partyQuestId={} userId={}",
                            data.partyQuestId(), userId);
                    skipped++;
                    continue;
                }

                // 멤버별 Slack 호출을 RetryTemplate(3회, 0.2초 간격)으로 감싼다.
                // 이력 테이블이 없는 지금, 테이블 없이 유실 가능성을 줄이는 개선이다
                // -- 순간적인 네트워크 오류나 Slack 의 일시적 응답 실패는 여기서 로컬 재시도로 흡수한다.
                // 한계: 이력 테이블이 없으므로 Slack 이 수 초 이상 죽어 있어 3회가 모두 실패하면
                // 그 멤버는 여전히 재발송 없이 유실된다 (아래 TODO 참고).
                // 재시도가 다 실패해도 예외는 아래 catch 에서 삼킨다
                // -- 밖으로 던지면 Kafka 가 메시지 전체를 재전송해서 이미 받은 멤버가 중복 DM 을 받는다.
                SlackMessageResult result = retryTemplate.execute(
                        context -> partyQuestSlackNotifier.sendCreated(userInfo.slackId(), data));
                sent++;
                log.info("파티 퀘스트 생성 Slack 발송 완료. partyQuestId={} userId={} channel={}",
                        data.partyQuestId(), userId, result.channel());

            } catch (Exception e) {
                failed++;
                log.error("파티 퀘스트 생성 Slack 발송 실패. 다음 멤버로 진행한다. partyQuestId={} userId={}",
                        data.partyQuestId(), userId, e);
            }
        }

        log.info("파티 퀘스트 생성 알림 처리 종료. partyQuestId={} 전체={} 성공={} 건너뜀={} 실패={}",
                data.partyQuestId(), data.members().size(), sent, skipped, failed);
    }
}
