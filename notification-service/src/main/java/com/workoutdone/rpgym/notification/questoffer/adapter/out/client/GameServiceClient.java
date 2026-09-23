package com.workoutdone.rpgym.notification.questoffer.adapter.out.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * game-service의 Quest 제안 수락/거절 API를 호출한다.
 * user-service 연동과 동일하게 게이트웨이를 거치지 않고 Eureka로 바로 붙는다 --
 * /internal 경로라 게이트웨이가 외부 접근을 막아두므로 내부 호출만 가능하다.
 *
 * X-User-Id는 게이트웨이가 JWT에서 뽑아 채워주는 값인데, 여기서는 그 경로가 없으므로
 * quest_offers에 이미 저장해둔 userId를 우리가 직접 채워 보낸다.
 *
 * 응답 본문은 쓰지 않는다 -- 성공/실패 여부만으로 충분함
 * ////TO-DO: Slack 메시지를 결과에 맞게 갱신하는 건 후속 작업이다.
 */
@FeignClient(name = "game-service")
public interface GameServiceClient {

    @PostMapping("/api/v2/internal/games/quest-suggestions/{suggestionId}/accept")
    ResponseEntity<Void> accept(@PathVariable UUID suggestionId, @RequestHeader("X-User-Id") UUID userId);

    @PostMapping("/api/v2/internal/games/quest-suggestions/{suggestionId}/reject")
    ResponseEntity<Void> reject(@PathVariable UUID suggestionId, @RequestHeader("X-User-Id") UUID userId);
}
