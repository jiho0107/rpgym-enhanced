package com.workoutdone.rpgym.user.user.adapter.in.web;

import com.workoutdone.rpgym.common.constant.HeaderConstants;
import com.workoutdone.rpgym.common.security.RequireRole;
import com.workoutdone.rpgym.common.security.UserRole;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqLoginDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqLogoutDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqRefreshTokenDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqSignUpDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqUpdateMyAccountDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ResLoginDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ResMyAccountDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ResSignUpDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ResUpdateMyAccountDto;
import com.workoutdone.rpgym.user.user.application.GetMyAccountResult;
import com.workoutdone.rpgym.user.user.application.GetMyAccountService;
import com.workoutdone.rpgym.user.user.application.LoginResult;
import com.workoutdone.rpgym.user.user.application.LoginService;
import com.workoutdone.rpgym.user.user.application.LogoutService;
import com.workoutdone.rpgym.user.user.application.RefreshTokenResult;
import com.workoutdone.rpgym.user.user.application.RefreshTokenService;
import com.workoutdone.rpgym.user.user.application.SignUpResult;
import com.workoutdone.rpgym.user.user.application.SignUpService;
import com.workoutdone.rpgym.user.user.application.UpdateMyAccountResult;
import com.workoutdone.rpgym.user.user.application.UpdateMyAccountService;
import com.workoutdone.rpgym.user.user.application.WithdrawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SignUpService signUpService;
    private final LoginService loginService;
    private final LogoutService logoutService;
    private final RefreshTokenService refreshTokenService;
    private final GetMyAccountService getMyAccountService;
    private final UpdateMyAccountService updateMyAccountService;
    private final WithdrawService withdrawService;

    @PostMapping("/signup")
    public ResponseEntity<ResSignUpDto> signUp(@Valid @RequestBody ReqSignUpDto request) {
        SignUpResult result = signUpService.signUp(request.toCommand());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ResSignUpDto.from(result));
    }

    @PostMapping("/login")
    public ResponseEntity<ResLoginDto> login(@Valid @RequestBody ReqLoginDto request) {
        LoginResult result = loginService.login(request.toCommand());

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ResLoginDto.from(result));
    }

    // 인증된 사용자(USER/ADMIN) 본인 소유의 refreshToken만 폐기 가능
    // X-User-Role 검증은 RoleAuthorizationInterceptor가 처리
    // Gateway는 Authorization 헤더를 제거하지 않고 그대로 전달하므로, 여기서 이 요청의 Access Token(Blacklist 등록 대상)을 꺼낸다.
    // Blacklist는 추가 방어선이라 이 헤더가 없어도 요청 자체를 거부하지 않는다
    @RequireRole({UserRole.USER, UserRole.ADMIN})
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(HeaderConstants.USER_ID) UUID userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ReqLogoutDto request
    ) {
        logoutService.logout(request.toCommand(userId, extractBearerToken(authorization)));

        return ResponseEntity.noContent().build();
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }

        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    // 게이트웨이 표준 인증(JWT)을 거치지 않는 API
    // 요청 바디의 refreshToken 자체를 자격증명으로 삼아 RefreshTokenService가 직접 검증하므로 X-User-Id/@RequireRole을 쓰지 않음
    @PostMapping("/refresh")
    public ResponseEntity<ResLoginDto> refresh(@Valid @RequestBody ReqRefreshTokenDto request) {
        RefreshTokenResult result = refreshTokenService.refresh(request.toCommand());

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ResLoginDto.from(result));
    }

    // USER role만 본인 계정 조회 가능 (ADMIN 제외)
    // X-User-Role 검증은 RoleAuthorizationInterceptor가 처리
    @RequireRole(UserRole.USER)
    @GetMapping("/me")
    public ResponseEntity<ResMyAccountDto> getMyAccount(
            @RequestHeader(HeaderConstants.USER_ID) UUID userId
    ) {
        GetMyAccountResult result = getMyAccountService.getMyAccount(userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ResMyAccountDto.from(result));
    }

    // USER role만 본인 계정 수정 가능 (ADMIN 제외)
    // X-User-Role 검증은 RoleAuthorizationInterceptor가 처리
    @RequireRole(UserRole.USER)
    @PatchMapping("/me")
    public ResponseEntity<ResUpdateMyAccountDto> updateMyAccount(
            @RequestHeader(HeaderConstants.USER_ID) UUID userId,
            @Valid @RequestBody ReqUpdateMyAccountDto request
    ) {
        UpdateMyAccountResult result = updateMyAccountService.updateMyAccount(request.toCommand(userId));

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ResUpdateMyAccountDto.from(result));
    }

    // USER role만 본인 계정 탈퇴 가능 (ADMIN 제외)
    // X-User-Role 검증은 RoleAuthorizationInterceptor가 처리
    // 이미 탈퇴한 계정에 다시 호출해도 에러 없이 204로 응답한다(멱등 처리)
    @RequireRole(UserRole.USER)
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdrawMyAccount(
            @RequestHeader(HeaderConstants.USER_ID) UUID userId
    ) {
        withdrawService.withdraw(userId);

        return ResponseEntity.noContent().build();
    }
}
