package com.workoutdone.rpgym.notification.questoffer.adapter.out.persistence;

import com.workoutdone.rpgym.notification.config.JpaAuditingConfig;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferAction;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferCloseReason;
import com.workoutdone.rpgym.notification.questoffer.domain.QuestOfferStatus;
import com.workoutdone.rpgym.notification.questoffer.domain.aggregate.QuestOffer;
import com.workoutdone.rpgym.notification.questoffer.domain.repo.QuestOfferRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway로 만든 실제 PostgreSQL 스키마 위에서 QuestOffer 매핑과 낙관적 락을 검증한다.
 * 단위 테스트(QuestOfferTest)는 메모리 안의 상태 전이만 보므로,
 * "DB에 어떤 값으로 저장되는지"와 "동시에 들어온 클릭을 DB가 어떻게 막는지"는 여기서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({JpaAuditingConfig.class, QuestOfferRepositoryImpl.class})
@TestPropertySource(properties = {
        "spring.flyway.schemas=notification_service",
        "spring.jpa.properties.hibernate.default_schema=notification_service",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DisplayName("quest_offers 영속성")
class QuestOfferPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TITLE = "20분 산책하기";
    private static final String DM_CHANNEL = "D0123456789";
    private static final String SENT_TS = "1732147200.000100";
    // Slack response_url은 길이가 보장되지 않는다. VARCHAR(255)였다면 잘렸을 길이로 TEXT 컬럼을 확인한다.
    private static final String LONG_RESPONSE_URL = "https://hooks.slack.com/actions/T0/1/" + "a".repeat(300);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private QuestOfferRepository questOfferRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static Instant now() {
        // PostgreSQL TIMESTAMPTZ는 마이크로초까지만 저장하므로 비교가 어긋나지 않게 맞춘다.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private QuestOffer newSentOffer() {
        QuestOffer offer = QuestOffer.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TITLE);
        offer.markSent(DM_CHANNEL, SENT_TS, now());
        return offer;
    }

    private Map<String, Object> rawRow(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM notification_service.quest_offers WHERE id = ?", id);
    }

    @Test
    @DisplayName("클릭 접수부터 종료까지 상태 전이 결과가 DB에 그대로 저장되고 다시 읽힌다")
    void persistsFullDecisionLifecycle() {
        Instant clickedAt = now();
        QuestOffer offer = newSentOffer();
        offer.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, "1732147100.000050", LONG_RESPONSE_URL, clickedAt);
        offer.claim(clickedAt, LEASE);
        offer.close(QuestOfferCloseReason.EXPIRED);
        questOfferRepository.save(offer);
        entityManager.flush();
        entityManager.clear();

        QuestOffer reloaded = questOfferRepository.findBySuggestionId(offer.getSuggestionId()).orElseThrow();

        assertThat(reloaded.getTitle()).isEqualTo(TITLE);
        assertThat(reloaded.getStatus()).isEqualTo(QuestOfferStatus.CLOSED);
        assertThat(reloaded.getRequestedAction()).isEqualTo(QuestOfferAction.ACCEPT);
        assertThat(reloaded.getCloseReason()).isEqualTo(QuestOfferCloseReason.EXPIRED);
        assertThat(reloaded.getClickedChannelId()).isEqualTo(DM_CHANNEL);
        assertThat(reloaded.getClickedMessageTs()).isEqualTo("1732147100.000050");
        assertThat(reloaded.getResponseUrl()).isEqualTo(LONG_RESPONSE_URL);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isNull();
        assertThat(reloaded.getRespondedAt()).isEqualTo(clickedAt);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("enum은 이름 문자열로 저장된다 (명세서의 컬럼 값과 일치)")
    void storesEnumsAsNames() {
        QuestOffer offer = newSentOffer();
        offer.requestDecision(QuestOfferAction.REJECT, DM_CHANNEL, SENT_TS, null, now());
        offer.claim(now(), LEASE);
        offer.close(QuestOfferCloseReason.SUPERSEDED);
        questOfferRepository.save(offer);
        entityManager.flush();

        Map<String, Object> row = rawRow(offer.getId());

        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("requested_action")).isEqualTo("REJECT");
        assertThat(row.get("close_reason")).isEqualTo("SUPERSEDED");
    }

    @Test
    @DisplayName("PROCESSING 행의 next_attempt_at이 저장되어 스케줄러 조회 조건(status, next_attempt_at)으로 찾을 수 있다")
    void processingRowIsFoundBySchedulerCondition() {
        Instant clickedAt = now().minusSeconds(1);
        QuestOffer offer = newSentOffer();
        offer.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, SENT_TS, null, clickedAt);
        questOfferRepository.save(offer);
        entityManager.flush();

        Integer due = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_service.quest_offers "
                        + "WHERE status = 'PROCESSING' AND next_attempt_at <= now() AND id = ?",
                Integer.class, offer.getId());
        assertThat(due).isEqualTo(1);

        // 선점하면 lease 동안은 조회 조건에서 빠진다.
        offer.claim(now(), LEASE);
        entityManager.flush();

        Integer dueAfterClaim = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_service.quest_offers "
                        + "WHERE status = 'PROCESSING' AND next_attempt_at <= now() AND id = ?",
                Integer.class, offer.getId());
        assertThat(dueAfterClaim).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 제안에 클릭 두 개가 동시에 들어오면 먼저 커밋한 클릭만 남고, 늦은 쪽은 낙관적 락으로 실패한다")
    void concurrentClicksKeepOnlyFirstCommittedClick() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TransactionTemplate newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        QuestOffer saved = tx.execute(status -> questOfferRepository.save(newSentOffer()));
        UUID suggestionId = saved.getSuggestionId();

        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(outer -> {
                // 클릭 A: 행을 읽었다 (아직 SENT)
                QuestOffer clickA = questOfferRepository.findBySuggestionId(suggestionId).orElseThrow();

                // 그 사이 클릭 B가 같은 행을 읽고, 접수하고, 먼저 커밋한다
                newTx.executeWithoutResult(inner -> {
                    QuestOffer clickB = questOfferRepository.findBySuggestionId(suggestionId).orElseThrow();
                    clickB.requestDecision(QuestOfferAction.REJECT, DM_CHANNEL, "card-B-ts", "url-B", now());
                });

                // 클릭 A는 자기가 읽은 시점 기준으로 SENT라서 접수를 시도한다 -> 커밋 시 버전 충돌
                clickA.requestDecision(QuestOfferAction.ACCEPT, DM_CHANNEL, "card-A-ts", "url-A", now());
            })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

            Map<String, Object> row = rawRow(saved.getId());
            assertThat(row.get("status")).isEqualTo("PROCESSING");
            assertThat(row.get("requested_action")).isEqualTo("REJECT");
            assertThat(row.get("clicked_message_ts")).isEqualTo("card-B-ts");
            assertThat(row.get("response_url")).isEqualTo("url-B");
        } finally {
            // NOT_SUPPORTED라 자동 롤백되지 않으므로 직접 지운다.
            jdbcTemplate.update("DELETE FROM notification_service.quest_offers WHERE id = ?", saved.getId());
        }
    }
}
