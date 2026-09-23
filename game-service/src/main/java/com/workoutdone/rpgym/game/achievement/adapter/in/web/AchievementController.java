package com.workoutdone.rpgym.game.achievement.adapter.in.web;

import com.workoutdone.rpgym.game.achievement.adapter.in.web.dto.AchievementResponse;
import com.workoutdone.rpgym.game.achievement.application.AchievementQueryService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 업적 조회 API.
 * 인증 주체는 게이트웨이가 넣어주는 X-User-Id 헤더에서 얻는다 (CharacterController 와 동일).
 */
@RestController
@RequestMapping("/api/v1/games/achievements")
@RequiredArgsConstructor
public class AchievementController {

    private final AchievementQueryService achievementQueryService;

    /** 내 업적 목록. 진행 행이 없는 업적도 0/미달성으로 포함되므로 항상 200 */
    @GetMapping("/me")
    public ResponseEntity<List<AchievementResponse>> getMyAchievements(
            @RequestHeader("X-User-Id") UUID userId
    ) {
        List<AchievementResponse> body = achievementQueryService.findMine(userId).stream()
                .map(AchievementResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
