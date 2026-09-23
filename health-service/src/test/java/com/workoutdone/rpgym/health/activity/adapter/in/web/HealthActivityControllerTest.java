package com.workoutdone.rpgym.health.activity.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workoutdone.rpgym.health.activity.application.*;
import com.workoutdone.rpgym.health.activity.domain.ActivitySource;
import com.workoutdone.rpgym.health.config.CommonExceptionHandlerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@WebMvcTest(HealthActivityController.class)
@Import(CommonExceptionHandlerConfig.class)
class HealthActivityControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    HealthActivitySyncUseCase healthActivitySyncUseCase;
    @MockitoBean
    HealthActivityQueryUseCase healthActivityQueryUseCase;

    private final UUID userId = UUID.randomUUID();

    private HealthActivityView view() {
        return new HealthActivityView(
                UUID.randomUUID(), userId, LocalDate.of(2026, 8, 28),
                Instant.parse("2026-08-28T01:30:00Z"),
                3100, 31, 155, ActivitySource.SYNTHETIC);
    }

    private static final String MEASURED_AT = "2026-08-28T01:30:00Z";

    private String body(Object steps) throws Exception {
        return body(steps, MEASURED_AT, "HEALTH_CONNECT");
    }

    private String body(Object steps, String measuredAt, String source) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "measuredAt", measuredAt,
                "steps", steps,
                "activeMinutes", 31,
                "activeCalories", 155,
                "source", source));
    }

    @Test
    @DisplayName("POST /sync — 신규 저장이면 201")
    void sync_newSnapshot_returns201() throws Exception {
        given(healthActivitySyncUseCase.sync(any()))
                .willReturn(new HealthActivitySyncResult(view(), true));

        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.steps").value(3100))
                .andExpect(jsonPath("$.source").value("SYNTHETIC"));
    }

    @Test
    @DisplayName("POST /sync — 이미 있는 시점이면 200")
    void sync_existingMeasuredAt_returns200() throws Exception {
        given(healthActivitySyncUseCase.sync(any()))
                .willReturn(new HealthActivitySyncResult(view(), false));

        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /sync — 음수 지표는 400 INVALID_INPUT")
    void sync_negativeMetric_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(-1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("steps"));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 401 UNAUTHORIZED")
    void missingUserIdHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/health-activities/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /today — 이력이 없어도 200 + 0값")
    void getToday_noHistory_returns200WithZeroValues() throws Exception {
        given(healthActivityQueryUseCase.getToday(any()))
                .willReturn(HealthActivityView.empty(userId, LocalDate.of(2026, 8, 28)));

        mockMvc.perform(get("/api/v1/health-activities/today")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps").value(0))
                .andExpect(jsonPath("$.activityId").doesNotExist());
    }

    @Test
    @DisplayName("POST /sync — HEALTH_CONNECT 출처는 받아서 유스케이스로 넘긴다")
    void sync_healthConnectSource_passesToUseCase() throws Exception {
        given(healthActivitySyncUseCase.sync(any()))
                .willReturn(new HealthActivitySyncResult(view(), true));

        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100, MEASURED_AT, "HEALTH_CONNECT")))
                .andExpect(status().isCreated());

        ArgumentCaptor<SyncHealthActivityCommand> captor =
                ArgumentCaptor.forClass(SyncHealthActivityCommand.class);
        then(healthActivitySyncUseCase).should().sync(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(ActivitySource.HEALTH_CONNECT);
    }

    @Test
    @DisplayName("POST /sync — SYNTHETIC 출처는 외부 요청으로 받지 않는다 (400)")
    void sync_syntheticSourceFromClient_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100, MEASURED_AT, "SYNTHETIC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("source"));

        then(healthActivitySyncUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("POST /sync — 허용 오차를 넘는 미래 측정 시점은 400")
    void sync_futureMeasuredAtBeyondTolerance_returns400() throws Exception {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100, future, "HEALTH_CONNECT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fields[0].field").value("measuredAt"));

        then(healthActivitySyncUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("POST /sync — measuredAt의 밀리초는 초 단위로 절삭되어 넘어간다")
    void sync_measuredAtWithMillis_truncatedToSeconds() throws Exception {
        given(healthActivitySyncUseCase.sync(any()))
                .willReturn(new HealthActivitySyncResult(view(), true));

        mockMvc.perform(post("/api/v1/health-activities/sync")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3100, "2026-08-28T01:30:00.789Z", "HEALTH_CONNECT")))
                .andExpect(status().isCreated());

        ArgumentCaptor<SyncHealthActivityCommand> captor =
                ArgumentCaptor.forClass(SyncHealthActivityCommand.class);
        then(healthActivitySyncUseCase).should().sync(captor.capture());
        assertThat(captor.getValue().measuredAt())
                .isEqualTo(Instant.parse("2026-08-28T01:30:00Z"));
    }
}