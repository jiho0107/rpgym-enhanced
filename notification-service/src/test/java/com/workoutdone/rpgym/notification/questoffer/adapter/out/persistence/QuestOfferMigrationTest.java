package com.workoutdone.rpgym.notification.questoffer.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 마이그레이션이 "이미 행이 들어 있는 V1 테이블"에 적용될 때를 검증한다.
 * 운영 DB는 빈 테이블이 아니라 기존 행이 있는 상태에서 V2를 맞게 되므로,
 * 빈 DB에 V1+V2를 한 번에 올리는 스프링 컨텍스트 테스트로는 title 채우기(backfill)를 확인할 수 없다.
 * 그래서 Flyway를 직접 다뤄 V1까지만 올리고 -> 기존 행을 넣고 -> V2를 올린다.
 */
@Testcontainers
@DisplayName("quest_offers V2 마이그레이션")
class QuestOfferMigrationTest {

    private static final String SCHEMA = "notification_service";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    @BeforeEach
    void migrateToV1() {
        // 테스트마다 빈 스키마에서 V1까지만 올린 상태로 시작한다.
        flyway("1").clean();
        flyway("1").migrate();
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private UUID insertV1Row(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO notification_service.quest_offers (id, suggestion_id, user_id, status) VALUES (?, ?, ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), status);
        return id;
    }

    @Test
    @DisplayName("기존 행이 있어도 V2가 적용되고, 기존 행의 title은 기본 문구로, attempt_count는 0으로 채워진다")
    void backfillsExistingRows() {
        UUID sentId = insertV1Row("SENT");
        UUID acceptedId = insertV1Row("ACCEPTED");

        flyway("latest").migrate();

        for (UUID id : new UUID[]{sentId, acceptedId}) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, attempt_count, requested_action, clicked_channel_id, clicked_message_ts, "
                            + "response_url, next_attempt_at, close_reason "
                            + "FROM notification_service.quest_offers WHERE id = ?", id);

            assertThat(row.get("title")).isEqualTo("오늘의 퀘스트");
            assertThat(row.get("attempt_count")).isEqualTo(0);
            assertThat(row.get("requested_action")).isNull();
            assertThat(row.get("clicked_channel_id")).isNull();
            assertThat(row.get("clicked_message_ts")).isNull();
            assertThat(row.get("response_url")).isNull();
            assertThat(row.get("next_attempt_at")).isNull();
            assertThat(row.get("close_reason")).isNull();
        }
    }

    @Test
    @DisplayName("V2 이후에는 title 없이 행을 넣을 수 없다 (NOT NULL)")
    void titleIsNotNullAfterV2() {
        flyway("latest").migrate();

        assertThatThrownBy(() -> insertV1Row("PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("스케줄러 조회용 인덱스는 PROCESSING 행만 담는 부분 인덱스로 만들어진다")
    void createsPartialIndexForProcessingRows() {
        flyway("latest").migrate();

        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                String.class, SCHEMA, "ix_quest_offers_processing_next_attempt_at");

        assertThat(indexDef)
                .contains("(next_attempt_at)")
                .contains("WHERE")
                .contains("'PROCESSING'");
    }
}
