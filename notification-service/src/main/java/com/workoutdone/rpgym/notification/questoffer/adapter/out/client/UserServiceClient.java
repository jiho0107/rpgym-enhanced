package com.workoutdone.rpgym.notification.questoffer.adapter.out.client;

import com.workoutdone.rpgym.notification.questoffer.adapter.out.client.dto.UserInfoResponse;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * health-service/game-service의 UserServiceClient와 동일한 내부 API를 호출한다.
 * 게이트웨이가 /api/v1/internal/** 외부 인입을 차단하므로 별도 인증 헤더가 필요 없다.
 */
@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/api/v1/internal/users/{userId}")
    UserInfoResponse getUserInfo(@PathVariable UUID userId);
}
