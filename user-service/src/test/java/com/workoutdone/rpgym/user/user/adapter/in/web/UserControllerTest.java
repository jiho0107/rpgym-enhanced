package com.workoutdone.rpgym.user.user.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workoutdone.rpgym.common.exception.BaseException;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.common.security.UserRole;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqLoginDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqRefreshTokenDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqSignUpDto;
import com.workoutdone.rpgym.user.user.adapter.in.web.dto.ReqUpdateMyAccountDto;
import com.workoutdone.rpgym.user.user.application.GetMyAccountResult;
import com.workoutdone.rpgym.user.user.application.GetMyAccountService;
import com.workoutdone.rpgym.user.user.application.LoginResult;
import com.workoutdone.rpgym.user.user.application.LoginService;
import com.workoutdone.rpgym.user.user.application.LogoutCommand;
import com.workoutdone.rpgym.user.user.application.LogoutService;
import com.workoutdone.rpgym.user.user.application.RefreshTokenResult;
import com.workoutdone.rpgym.user.user.application.RefreshTokenService;
import com.workoutdone.rpgym.user.user.application.SignUpCommand;
import com.workoutdone.rpgym.user.user.application.SignUpResult;
import com.workoutdone.rpgym.user.user.application.SignUpService;
import com.workoutdone.rpgym.user.user.application.UpdateMyAccountResult;
import com.workoutdone.rpgym.user.user.application.UpdateMyAccountService;
import com.workoutdone.rpgym.user.user.application.WithdrawService;
import com.workoutdone.rpgym.user.user.domain.User;
import com.workoutdone.rpgym.user.user.domain.UserStatus;
import com.workoutdone.rpgym.user.user.domain.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    private static final String SIGNUP_URL = "/api/v1/users/signup";
    private static final String LOGIN_URL = "/api/v1/users/login";
    private static final String LOGOUT_URL = "/api/v1/users/logout";
    private static final String ME_URL = "/api/v1/users/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SignUpService signUpService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private LogoutService logoutService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private GetMyAccountService getMyAccountService;

    @MockitoBean
    private UpdateMyAccountService updateMyAccountService;

    @MockitoBean
    private WithdrawService withdrawService;

    private ReqSignUpDto validRequest() {
        return ReqSignUpDto.builder()
                .email("healthuser@example.com")
                .password("myPassw0rd!")
                .nickname("헬스퀘스트유저")
                .slackId("U0123ABC456")
                .build();
    }

    @Test
    @DisplayName("정상 요청이면 201과 함께 회원 정보를 반환한다")
    void signUp_success() throws Exception {
        SignUpResult result = SignUpResult.builder()
                .id(UUID.randomUUID())
                .email("healthuser@example.com")
                .nickname("헬스퀘스트유저")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        given(signUpService.signUp(any())).willReturn(result);

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("healthuser@example.com"))
                .andExpect(jsonPath("$.nickname").value("헬스퀘스트유저"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("이메일/닉네임/슬랙 아이디의 앞뒤 공백은 트리밍되고, 비밀번호는 트리밍되지 않은 채로 서비스에 전달된다")
    void signUp_trimsWhitespaceExceptPassword() throws Exception {
        SignUpResult result = SignUpResult.builder()
                .id(UUID.randomUUID())
                .email("healthuser@example.com")
                .nickname("헬스퀘스트유저")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        given(signUpService.signUp(any())).willReturn(result);

        String rawJson = """
                {
                  "email": "  healthuser@example.com  ",
                  "password": "  myPassw0rd!  ",
                  "nickname": "  헬스퀘스트유저  ",
                  "slackId": "  U0123ABC456  "
                }
                """;

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isCreated());

        ArgumentCaptor<SignUpCommand> captor = ArgumentCaptor.forClass(SignUpCommand.class);
        verify(signUpService).signUp(captor.capture());
        SignUpCommand command = captor.getValue();

        assertThat(command.getEmail()).isEqualTo("healthuser@example.com");
        assertThat(command.getNickname()).isEqualTo("헬스퀘스트유저");
        assertThat(command.getSlackId()).isEqualTo("U0123ABC456");
        assertThat(command.getRawPassword()).isEqualTo("  myPassw0rd!  ");
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void signUp_invalidEmail() throws Exception {
        ReqSignUpDto request = ReqSignUpDto.builder()
                .email("not-an-email")
                .password("myPassw0rd!")
                .nickname("닉네임")
                .slackId("U0123ABC456")
                .build();

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 400 INVALID_INPUT을 반환한다")
    void signUp_passwordTooShort() throws Exception {
        ReqSignUpDto request = ReqSignUpDto.builder()
                .email("healthuser@example.com")
                .password("short")
                .nickname("닉네임")
                .slackId("U0123ABC456")
                .build();

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("닉네임이 비어 있으면 400 INVALID_INPUT을 반환한다")
    void signUp_blankNickname() throws Exception {
        ReqSignUpDto request = ReqSignUpDto.builder()
                .email("healthuser@example.com")
                .password("myPassw0rd!")
                .nickname("")
                .slackId("U0123ABC456")
                .build();

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("이메일이 이미 사용 중이면 409 EMAIL_DUPLICATED를 반환한다")
    void signUp_duplicateEmail() throws Exception {
        given(signUpService.signUp(any())).willThrow(new BaseException(UserErrorCode.EMAIL_DUPLICATED));

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("닉네임이 이미 사용 중이면 409 NICKNAME_DUPLICATED를 반환한다")
    void signUp_duplicateNickname() throws Exception {
        given(signUpService.signUp(any())).willThrow(new BaseException(UserErrorCode.NICKNAME_DUPLICATED));

        mockMvc.perform(post(SIGNUP_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    private ReqLoginDto validLoginRequest() {
        return ReqLoginDto.builder()
                .email("healthuser@example.com")
                .password("myPassw0rd!")
                .build();
    }

    @Test
    @DisplayName("이메일/비밀번호가 일치하면 200과 함께 토큰 정보를 반환한다")
    void login_success() throws Exception {
        LoginResult result = LoginResult.builder()
                .accessToken("access-token")
                .refreshToken("8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33")
                .tokenType("Bearer")
                .expiresIn(1800L)
                .build();
        given(loginService.login(any())).willReturn(result);

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLoginRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void login_invalidEmail() throws Exception {
        ReqLoginDto request = ReqLoginDto.builder()
                .email("not-an-email")
                .password("myPassw0rd!")
                .build();

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 400 INVALID_INPUT을 반환한다")
    void login_passwordTooShort() throws Exception {
        ReqLoginDto request = ReqLoginDto.builder()
                .email("healthuser@example.com")
                .password("short")
                .build();

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("이메일/비밀번호가 일치하지 않으면(또는 탈퇴한 계정이면) 401 LOGIN_FAILED를 반환한다")
    void login_loginFailed() throws Exception {
        given(loginService.login(any())).willThrow(new BaseException(UserErrorCode.LOGIN_FAILED));

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLoginRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("정지된 계정이면 403 ACCOUNT_SUSPENDED를 반환한다")
    void login_accountSuspended() throws Exception {
        given(loginService.login(any())).willThrow(new BaseException(UserErrorCode.ACCOUNT_SUSPENDED));

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLoginRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    private static final String REFRESH_URL = "/api/v1/users/refresh";

    @Test
    @DisplayName("유효한 refreshToken이면 200과 함께 새 토큰 정보를 반환한다")
    void refresh_success() throws Exception {
        RefreshTokenResult result = RefreshTokenResult.builder()
                .accessToken("new-access-token")
                .refreshToken("3c7f9a1e-2b8d-4e5c-9f01-8a2d6c4b7e19")
                .tokenType("Bearer")
                .expiresIn(1800L)
                .build();
        given(refreshTokenService.refresh(any())).willReturn(result);

        String rawJson = """
                {
                  "refreshToken": "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33"
                }
                """;

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("3c7f9a1e-2b8d-4e5c-9f01-8a2d6c4b7e19"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("refreshToken이 없으면 400 INVALID_INPUT을 반환한다")
    void refresh_missingToken() throws Exception {
        String rawJson = """
                {
                  "refreshToken": ""
                }
                """;

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("refreshToken이 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void refresh_invalidFormat() throws Exception {
        String rawJson = """
                {
                  "refreshToken": "not-a-uuid"
                }
                """;

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("존재하지 않거나 만료된 refreshToken이면 401 INVALID_REFRESH_TOKEN을 반환한다")
    void refresh_invalidRefreshToken() throws Exception {
        given(refreshTokenService.refresh(any()))
                .willThrow(new BaseException(UserErrorCode.INVALID_REFRESH_TOKEN));

        String rawJson = """
                {
                  "refreshToken": "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33"
                }
                """;

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("정지된 계정이면 403 ACCOUNT_SUSPENDED를 반환한다")
    void refresh_accountSuspended() throws Exception {
        given(refreshTokenService.refresh(any()))
                .willThrow(new BaseException(UserErrorCode.ACCOUNT_SUSPENDED));

        String rawJson = """
                {
                  "refreshToken": "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33"
                }
                """;

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    private String validLogoutRequestJson() {
        return """
                {
                  "refreshToken": "8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33"
                }
                """;
    }

    @Test
    @DisplayName("USER role이고 본인 소유 refreshToken이면 204를 반환한다")
    void logout_successAsUser() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());

        verify(logoutService).logout(any());
    }

    @Test
    @DisplayName("Authorization 헤더의 Bearer 토큰이 Access Token으로 추출되어 로그아웃 처리에 전달된다(Blacklist 등록 대상)")
    void logout_passesBearerAccessTokenToService() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER")
                        .header("Authorization", "Bearer header.payload.signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).logout(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getRefreshToken()).isEqualTo("8f3c1e2a-7b4d-4c9e-9a11-3f6d9c0b7e33");
        assertThat(captor.getValue().getAccessToken()).isEqualTo("header.payload.signature");
    }

    @Test
    @DisplayName("Bearer 스킴은 대소문자를 구분하지 않는다")
    void logout_bearerSchemeIsCaseInsensitive() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .header("Authorization", "bearer header.payload.signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).logout(captor.capture());
        assertThat(captor.getValue().getAccessToken()).isEqualTo("header.payload.signature");
    }

    @Test
    @DisplayName("Authorization 헤더가 없어도 요청을 거부하지 않고 204를 반환한다(Blacklist는 추가 방어선)")
    void logout_withoutAuthorizationHeader_stillSucceeds() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).logout(captor.capture());
        assertThat(captor.getValue().getAccessToken()).isNull();
    }

    @Test
    @DisplayName("Bearer 방식이 아닌 Authorization 헤더는 Access Token으로 취급하지 않는다")
    void logout_nonBearerAuthorization_accessTokenIsNull() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LogoutCommand> captor = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(logoutService).logout(captor.capture());
        assertThat(captor.getValue().getAccessToken()).isNull();
    }

    @Test
    @DisplayName("ADMIN role이어도 204를 반환한다 (USER/ADMIN 둘 다 허용)")
    void logout_successAsAdmin() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("refreshToken이 없으면 400 INVALID_INPUT을 반환한다")
    void logout_missingToken() throws Exception {
        String rawJson = """
                {
                  "refreshToken": ""
                }
                """;

        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("refreshToken이 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void logout_invalidFormat() throws Exception {
        String rawJson = """
                {
                  "refreshToken": "not-a-uuid"
                }
                """;

        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("다른 사용자 소유의 refreshToken이면 403 FORBIDDEN을 반환한다")
    void logout_forbidden() throws Exception {
        willThrow(new BaseException(CommonErrorCode.FORBIDDEN))
                .given(logoutService).logout(any());

        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Role이 USER/ADMIN이 아니면 403 FORBIDDEN을 반환한다")
    void logout_disallowedRole() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "GUEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 둘 다 없으면 401 UNAUTHORIZED를 반환한다")
    void logout_noHeaders() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Role 헤더만 없으면 401 UNAUTHORIZED를 반환한다")
    void logout_missingRoleHeader() throws Exception {
        mockMvc.perform(post(LOGOUT_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLogoutRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 있으면 200과 함께 내 계정 정보를 반환한다")
    void getMyAccount_success() throws Exception {
        UUID userId = UUID.randomUUID();
        GetMyAccountResult result = GetMyAccountResult.builder()
                .id(userId)
                .email("healthuser@example.com")
                .nickname("헬스퀘스트유저")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .slackId("U0123ABC456")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        given(getMyAccountService.getMyAccount(userId)).willReturn(result);

        mockMvc.perform(get(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("healthuser@example.com"))
                .andExpect(jsonPath("$.nickname").value("헬스퀘스트유저"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.slackId").value("U0123ABC456"));
    }

    @Test
    @DisplayName("X-User-Role이 ADMIN이면 403 FORBIDDEN을 반환한다")
    void getMyAccount_adminForbidden() throws Exception {
        mockMvc.perform(get(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Role이 USER가 아니면 403 FORBIDDEN을 반환한다")
    void getMyAccount_disallowedRole() throws Exception {
        mockMvc.perform(get(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "GUEST"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 둘 다 없으면 401 UNAUTHORIZED를 반환한다")
    void getMyAccount_noHeaders() throws Exception {
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Role 헤더만 없으면 401 UNAUTHORIZED를 반환한다")
    void getMyAccount_missingRoleHeader() throws Exception {
        mockMvc.perform(get(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("존재하지 않거나 탈퇴한 사용자면 404 USER_NOT_FOUND를 반환한다")
    void getMyAccount_userNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        given(getMyAccountService.getMyAccount(userId)).willThrow(new BaseException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private ReqUpdateMyAccountDto validUpdateRequest() {
        return ReqUpdateMyAccountDto.builder()
                .nickname("새닉네임")
                .slackId("U0999XYZ000")
                .build();
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 있으면 200과 함께 수정된 계정 정보를 반환한다")
    void updateMyAccount_success() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateMyAccountResult result = UpdateMyAccountResult.builder()
                .id(userId)
                .email("healthuser@example.com")
                .nickname("새닉네임")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .slackId("U0999XYZ000")
                .updatedAt(LocalDateTime.now())
                .build();
        given(updateMyAccountService.updateMyAccount(any())).willReturn(result);

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.slackId").value("U0999XYZ000"));
    }

    @Test
    @DisplayName("닉네임이 50자를 초과하면 400 INVALID_INPUT을 반환한다")
    void updateMyAccount_nicknameTooLong() throws Exception {
        ReqUpdateMyAccountDto request = ReqUpdateMyAccountDto.builder()
                .nickname("가".repeat(51))
                .build();

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("newPassword가 8자 미만이면 400 INVALID_INPUT을 반환한다")
    void updateMyAccount_newPasswordTooShort() throws Exception {
        ReqUpdateMyAccountDto request = ReqUpdateMyAccountDto.builder()
                .currentPassword("myPassw0rd!")
                .newPassword("short")
                .build();

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임이면 409 NICKNAME_DUPLICATED를 반환한다")
    void updateMyAccount_nicknameDuplicated() throws Exception {
        given(updateMyAccountService.updateMyAccount(any()))
                .willThrow(new BaseException(UserErrorCode.NICKNAME_DUPLICATED));

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    @Test
    @DisplayName("존재하지 않거나 탈퇴한 사용자면 404 USER_NOT_FOUND를 반환한다")
    void updateMyAccount_userNotFound() throws Exception {
        given(updateMyAccountService.updateMyAccount(any()))
                .willThrow(new BaseException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 요청과 동시에 수정되어 낙관적 락(@Version) 충돌이 나면 409 CONFLICT를 반환한다")
    void updateMyAccount_optimisticLockConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        given(updateMyAccountService.updateMyAccount(any()))
                .willThrow(new ObjectOptimisticLockingFailureException(User.class, userId));

        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("X-User-Role이 ADMIN이면 403 FORBIDDEN을 반환한다")
    void updateMyAccount_adminForbidden() throws Exception {
        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Role이 USER가 아니면 403 FORBIDDEN을 반환한다")
    void updateMyAccount_disallowedRole() throws Exception {
        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "GUEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 둘 다 없으면 401 UNAUTHORIZED를 반환한다")
    void updateMyAccount_noHeaders() throws Exception {
        mockMvc.perform(patch(ME_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Role 헤더만 없으면 401 UNAUTHORIZED를 반환한다")
    void updateMyAccount_missingRoleHeader() throws Exception {
        mockMvc.perform(patch(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 있으면 204를 반환하고 회원 탈퇴를 수행한다")
    void withdraw_success() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isNoContent());

        verify(withdrawService).withdraw(userId);
    }

    @Test
    @DisplayName("X-User-Role이 ADMIN이면 403 FORBIDDEN을 반환한다")
    void withdraw_adminForbidden() throws Exception {
        mockMvc.perform(delete(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Role이 USER가 아니면 403 FORBIDDEN을 반환한다")
    void withdraw_disallowedRole() throws Exception {
        mockMvc.perform(delete(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "GUEST"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("X-User-Id/X-User-Role 헤더가 둘 다 없으면 401 UNAUTHORIZED를 반환한다")
    void withdraw_noHeaders() throws Exception {
        mockMvc.perform(delete(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("X-User-Role 헤더만 없으면 401 UNAUTHORIZED를 반환한다")
    void withdraw_missingRoleHeader() throws Exception {
        mockMvc.perform(delete(ME_URL)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 404 USER_NOT_FOUND를 반환한다")
    void withdraw_userNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        willThrow(new BaseException(UserErrorCode.USER_NOT_FOUND))
                .given(withdrawService).withdraw(userId);

        mockMvc.perform(delete(ME_URL)
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
