/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 使用真实 openGauss 验证 Runtime Session MyBatis 映射与事务边界。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionRepositoryOpenGaussIT {
    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository repository;

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startContext() {
        requireProperty("gaussdb.it.url");
        requireProperty("gaussdb.it.username");
        requireProperty("gaussdb.it.password");
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetDatabase() {
        repository = context.getBean(RuntimeSessionRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        jdbcTemplate.update("TRUNCATE TABLE t_session_materialized");
        jdbcTemplate.update("TRUNCATE TABLE t_session_sequences");
        jdbcTemplate.update("TRUNCATE TABLE t_session_entries");
        jdbcTemplate.update("TRUNCATE TABLE t_session_cleanup_task");
        jdbcTemplate.update("TRUNCATE TABLE t_session_tombstone");
        jdbcTemplate.update("TRUNCATE TABLE t_sessions");
    }

    @Test
    void createsAndReadsAllRequiredSessionRows() {
        RuntimeSessionDTO session = newSession("session_db_create");

        repository.create(session);

        assertThat(repository.find(session.getId())).contains(session);
        assertThat(countSession(session.getId())).isOne();
        assertThat(count("t_session_sequences", session.getId())).isOne();
        assertThat(count("t_session_materialized", session.getId())).isOne();
    }

    @Test
    void createsExactTombstoneAndPendingCleanupTaskWhenDeleting() {
        RuntimeSessionDTO session = newSession("session_db_delete");
        repository.create(session);
        insertEntry(session.getId());
        OffsetDateTime deletedAt = OffsetDateTime.of(2026, 8, 18, 1, 30, 0, 0, ZoneOffset.UTC);

        assertThat(repository.beginDeletion(session.getId(), deletedAt)).isTrue();

        assertThat(repository.find(session.getId())).isEmpty();
        assertThat(count("t_session_tombstone", session.getId())).isOne();
        assertThat(count("t_session_cleanup_task", session.getId())).isOne();
        assertThat(cleanupState(session.getId())).isEqualTo("PENDING");
        assertThat(tombstoneColumns()).containsExactly("session_id", "deleted_at");

        assertThat(repository.claimCleanupTask(deletedAt, deletedAt.minusMinutes(5)))
                .contains(session.getId());
        assertThat(cleanupState(session.getId())).isEqualTo("RUNNING");
        repository.completeCleanup(session.getId());

        assertThat(count("t_session_entries", session.getId())).isZero();
        assertThat(count("t_session_sequences", session.getId())).isZero();
        assertThat(count("t_session_materialized", session.getId())).isZero();
        assertThat(count("t_session_cleanup_task", session.getId())).isZero();
        assertThat(count("t_session_tombstone", session.getId())).isOne();
    }

    @Test
    void returnsFalseWhenDeletingUnknownSession() {
        assertThat(repository.beginDeletion("session_missing", OffsetDateTime.now(ZoneOffset.UTC)))
                .isFalse();

        assertThat(count("t_session_tombstone", "session_missing")).isZero();
        assertThat(count("t_session_cleanup_task", "session_missing")).isZero();
    }

    @Test
    void rollsBackSessionInsertWhenSequenceInsertFails() {
        RuntimeSessionDTO session = newSession("session_db_create_rollback");
        jdbcTemplate.update(
                "INSERT INTO t_session_sequences (session_id, next_seq) VALUES (?, ?)", session.getId(), 1L);

        assertThatThrownBy(() -> repository.create(session)).isInstanceOf(RuntimeException.class);

        assertThat(countSession(session.getId())).isZero();
        assertThat(count("t_session_sequences", session.getId())).isOne();
        assertThat(count("t_session_materialized", session.getId())).isZero();
    }

    @Test
    void rollsBackDeleteWhenTombstoneInsertFails() {
        RuntimeSessionDTO session = newSession("session_db_delete_rollback");
        repository.create(session);
        jdbcTemplate.update(
                "INSERT INTO t_session_tombstone (session_id, deleted_at) VALUES (?, ?)",
                session.getId(),
                session.getCreatedAt());

        assertThatThrownBy(() -> repository.beginDeletion(session.getId(), session.getUpdatedAt()))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.find(session.getId())).contains(session);
        assertThat(count("t_session_cleanup_task", session.getId())).isZero();
    }

    @Test
    void neverReusesTombstonedSessionIdentifier() {
        RuntimeSessionDTO session = newSession("session_db_reserved");
        jdbcTemplate.update(
                "INSERT INTO t_session_tombstone (session_id, deleted_at) VALUES (?, ?)",
                session.getId(),
                session.getCreatedAt());

        assertThatThrownBy(() -> repository.create(session)).isInstanceOf(IllegalStateException.class);

        assertThat(countSession(session.getId())).isZero();
        assertThat(count("t_session_tombstone", session.getId())).isOne();
    }

    @Test
    void failedCleanupWaitsUntilRetryTime() {
        RuntimeSessionDTO session = newSession("session_db_retry");
        repository.create(session);
        OffsetDateTime now = session.getCreatedAt().plusMinutes(1);
        repository.beginDeletion(session.getId(), now);
        assertThat(repository.claimCleanupTask(now, now.minusMinutes(5))).contains(session.getId());

        repository.retryCleanup(session.getId(), now, now.plusMinutes(1), "TestFailure");

        assertThat(cleanupState(session.getId())).isEqualTo("RETRY");
        assertThat(repository.claimCleanupTask(now.plusSeconds(30), now.minusMinutes(5)))
                .isEmpty();
        assertThat(repository.claimCleanupTask(now.plusMinutes(1), now.minusMinutes(5)))
                .contains(session.getId());
    }

    @Test
    void acceptsAndAppendsStrictlyOrderedCurrentBranchEntries() {
        RuntimeSessionDTO session = newSession("session_db_events");
        repository.create(session);
        OffsetDateTime acceptedAt = session.getCreatedAt().plusSeconds(1);
        RuntimeEntryDTO user = newEntry(
                session.getId(), "entry_user", "user.message", acceptedAt, "{\"message\":\"hello\",\"file_ids\":[]}");

        UserEventAcceptance acceptance =
                repository.acceptUserEvent(session.getId(), session.getOwnerId(), user, acceptedAt);
        RuntimeEntryDTO assistant = newEntry(
                session.getId(),
                "entry_assistant",
                "assistant.message.completed",
                acceptedAt.plusSeconds(1),
                "{\"message\":{\"role\":\"assistant\",\"content\":[]},\"finish_reason\":\"stop\"}");
        repository.appendEntry(assistant);
        repository.finishExecution(session.getId(), acceptedAt.plusSeconds(2));

        assertThat(acceptance.status()).isEqualTo(Status.ACCEPTED);
        assertThat(user.getEntrySeq()).isEqualTo(1L);
        assertThat(user.getParentId()).isNull();
        assertThat(assistant.getEntrySeq()).isEqualTo(2L);
        assertThat(assistant.getParentId()).isEqualTo(user.getId());
        assertThat(repository.listCurrentBranch(session.getId(), 0, 10))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("entry_user", "entry_assistant");
        RuntimeSessionDTO finished = repository.find(session.getId()).orElseThrow();
        assertThat(finished.getState()).isEqualTo("idle");
        assertThat(finished.getResourceVersion()).isEqualTo(3L);
        assertThat(finished.getActiveLeafId()).isEqualTo("entry_assistant");
    }

    @Test
    void rollsBackEntryAppendWhenSessionIsNotRunning() {
        RuntimeSessionDTO session = newSession("session_db_idle_append");
        repository.create(session);
        RuntimeEntryDTO entry = newEntry(
                session.getId(),
                "entry_invalid",
                "assistant.message.completed",
                session.getCreatedAt().plusSeconds(1),
                "{\"message\":{\"role\":\"assistant\",\"content\":[]},\"finish_reason\":\"stop\"}");

        assertThatThrownBy(() -> repository.appendEntry(entry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session active leaf was not updated");

        assertThat(count("t_session_entries", session.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT next_seq FROM t_session_sequences WHERE session_id = ?", Long.class, session.getId()))
                .isEqualTo(1L);
    }

    @Test
    void onlyOneConcurrentUserEventCanBecomeActive() throws Exception {
        RuntimeSessionDTO session = newSession("session_db_event_race");
        repository.create(session);
        OffsetDateTime acceptedAt = session.getCreatedAt().plusSeconds(1);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acceptAfterLatch(
                    start, session, newEntry(session.getId(), "entry_race_1", "user.message", acceptedAt, "{}")));
            var second = executor.submit(() -> acceptAfterLatch(
                    start, session, newEntry(session.getId(), "entry_race_2", "user.message", acceptedAt, "{}")));
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Status.ACCEPTED, Status.BUSY);
        }

        assertThat(count("t_session_entries", session.getId())).isOne();
        assertThat(repository.find(session.getId()).orElseThrow().getState()).isEqualTo("running");
    }

    @Test
    void currentBranchQueryExcludesAbandonedBranch() {
        RuntimeSessionDTO session = newSession("session_db_branch");
        repository.create(session);
        insertBranchEntry(session.getId(), "entry_root", 1L, null);
        insertBranchEntry(session.getId(), "entry_abandoned", 2L, "entry_root");
        insertBranchEntry(session.getId(), "entry_current", 3L, "entry_root");
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry_current", session.getId());

        assertThat(repository.listCurrentBranch(session.getId(), 0, 10))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("entry_root", "entry_current");
    }

    @Test
    void modelChangeAtomicallyNormalizesThinkingAndAdvancesVersion() {
        RuntimeSessionDTO session = newSession("session_db_model_change");
        session.setThinking(true);
        repository.create(session);
        OffsetDateTime updatedAt = session.getCreatedAt().plusMinutes(1);

        SessionConfigurationUpdate update =
                repository.updateModel(session.getId(), session.getOwnerId(), 1L, "model-next", false, updatedAt);

        assertThat(update.status()).isEqualTo(SessionConfigurationUpdate.Status.UPDATED);
        RuntimeSessionDTO stored = repository.find(session.getId()).orElseThrow();
        assertThat(stored.getModelId()).isEqualTo("model-next");
        assertThat(stored.isThinking()).isFalse();
        assertThat(stored.getResourceVersion()).isEqualTo(2L);
        assertThat(stored.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void unchangedModelPreservesVersionTimestampAndThinking() {
        RuntimeSessionDTO session = newSession("session_db_model_noop");
        session.setThinking(true);
        repository.create(session);

        SessionConfigurationUpdate update = repository.updateModel(
                session.getId(),
                session.getOwnerId(),
                1L,
                session.getModelId(),
                false,
                session.getCreatedAt().plusHours(1));

        assertThat(update.status()).isEqualTo(SessionConfigurationUpdate.Status.UNCHANGED);
        RuntimeSessionDTO stored = repository.find(session.getId()).orElseThrow();
        assertThat(stored.isThinking()).isTrue();
        assertThat(stored.getResourceVersion()).isEqualTo(1L);
        assertThat(stored.getUpdatedAt()).isEqualTo(session.getUpdatedAt());
    }

    @Test
    void concurrentConfigurationChangesUseResourceVersionCas() throws Exception {
        RuntimeSessionDTO session = newSession("session_db_config_race");
        repository.create(session);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var model = executor.submit(() -> updateModelAfterLatch(start, session));
            var thinking = executor.submit(() -> updateThinkingAfterLatch(start, session));
            start.countDown();
            assertThat(List.of(model.get(5, TimeUnit.SECONDS), thinking.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            SessionConfigurationUpdate.Status.UPDATED,
                            SessionConfigurationUpdate.Status.VERSION_MISMATCH);
        }

        assertThat(repository.find(session.getId()).orElseThrow().getResourceVersion())
                .isEqualTo(2L);
    }

    private int count(String table, String sessionId) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE session_id = ?", Integer.class, sessionId);
        return result == null ? 0 : result;
    }

    private int countSession(String sessionId) {
        Integer result =
                jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_sessions WHERE id = ?", Integer.class, sessionId);
        return result == null ? 0 : result;
    }

    private String cleanupState(String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM t_session_cleanup_task WHERE session_id = ?", String.class, sessionId);
    }

    private List<String> tombstoneColumns() {
        return jdbcTemplate
                .queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema AND table_name = 't_session_tombstone' "
                        + "ORDER BY ordinal_position")
                .stream()
                .map(row -> String.valueOf(row.get("column_name")))
                .toList();
    }

    private void insertEntry(String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO t_session_entries "
                        + "(session_id, id, entry_seq, type, timestamp, payload) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))",
                sessionId,
                "entry-db-it",
                1L,
                "user.message",
                OffsetDateTime.of(2026, 8, 18, 1, 15, 0, 0, ZoneOffset.UTC),
                "{\"text\":\"hello\"}");
    }

    private void insertBranchEntry(String sessionId, String entryId, long sequence, String parentId) {
        jdbcTemplate.update(
                "INSERT INTO t_session_entries "
                        + "(session_id, id, entry_seq, parent_id, type, timestamp, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))",
                sessionId,
                entryId,
                sequence,
                parentId,
                "user.message",
                OffsetDateTime.of(2026, 8, 18, 1, 15, 0, 0, ZoneOffset.UTC),
                "{\"message\":\"hello\",\"file_ids\":[]}");
    }

    private Status acceptAfterLatch(CountDownLatch start, RuntimeSessionDTO session, RuntimeEntryDTO entry)
            throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository
                .acceptUserEvent(session.getId(), session.getOwnerId(), entry, entry.getTimestamp())
                .status();
    }

    private SessionConfigurationUpdate.Status updateModelAfterLatch(CountDownLatch start, RuntimeSessionDTO session)
            throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository
                .updateModel(
                        session.getId(),
                        session.getOwnerId(),
                        1L,
                        "model-race",
                        true,
                        session.getUpdatedAt().plusMinutes(1))
                .status();
    }

    private SessionConfigurationUpdate.Status updateThinkingAfterLatch(CountDownLatch start, RuntimeSessionDTO session)
            throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository
                .updateThinking(
                        session.getId(),
                        session.getOwnerId(),
                        1L,
                        true,
                        session.getUpdatedAt().plusMinutes(1))
                .status();
    }

    private static RuntimeEntryDTO newEntry(
            String sessionId, String entryId, String type, OffsetDateTime timestamp, String payload) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId(sessionId);
        entry.setId(entryId);
        entry.setType(type);
        entry.setTimestamp(timestamp);
        entry.setPayload(payload);
        return entry;
    }

    private static RuntimeSessionDTO newSession(String sessionId) {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 18, 1, 0, 0, 0, ZoneOffset.UTC);
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(sessionId);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setOwnerId("caller-db-it");
        session.setBundleRevision("revision-db-it");
        session.setModelId("model-db-it");
        session.setState("idle");
        session.setThinking(false);
        session.setResourceVersion(1L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd("/tmp/campusclaw-db-it");
        return session;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }

    /**
     * 真实 openGauss 集成测试的最小 Spring、MyBatis 与事务配置。
     *
     * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
     * @since [br_eCampusCore 25.1.0_Next]
     */
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = RuntimeSessionMapper.class)
    static class OpenGaussTestConfiguration {
        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl(System.getProperty("gaussdb.it.url"));
            configuration.setUsername(System.getProperty("gaussdb.it.username"));
            configuration.setPassword(System.getProperty("gaussdb.it.password"));
            configuration.setMaximumPoolSize(2);
            configuration.setPoolName("runtime-session-opengauss-it");
            return new HikariDataSource(configuration);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/session/RuntimeSessionMapper.xml"));
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        RuntimeSessionRepository runtimeSessionRepository(RuntimeSessionMapper mapper) {
            return new MyBatisRuntimeSessionRepository(mapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
