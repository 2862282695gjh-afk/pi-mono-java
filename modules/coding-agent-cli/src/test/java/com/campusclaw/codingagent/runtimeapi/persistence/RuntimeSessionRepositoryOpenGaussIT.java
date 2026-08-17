/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
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
