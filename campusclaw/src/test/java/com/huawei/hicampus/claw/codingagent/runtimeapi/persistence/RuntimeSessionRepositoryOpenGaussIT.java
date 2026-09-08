/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeUsageCause;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeExecutionControlMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.UserEventAcceptance.Status;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionModelConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionNamingService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionThinkingConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用真实 openGauss 验证 Runtime Session MyBatis 映射与事务边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionRepositoryOpenGaussIT {
    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository repository;

    private RuntimeExecutionControlRepository executionControls;

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
        executionControls = context.getBean(RuntimeExecutionControlRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        jdbcTemplate.update("TRUNCATE TABLE t_session_materialized");
        jdbcTemplate.update("TRUNCATE TABLE t_session_stats");
        jdbcTemplate.update("TRUNCATE TABLE t_session_records");
        jdbcTemplate.update("TRUNCATE TABLE t_session_sequences");
        jdbcTemplate.update("TRUNCATE TABLE t_session_execution_segment_events");
        jdbcTemplate.update("TRUNCATE TABLE t_session_execution_segments");
        jdbcTemplate.update("TRUNCATE TABLE t_session_executions");
        jdbcTemplate.update("TRUNCATE TABLE t_session_entries");
        jdbcTemplate.update("TRUNCATE TABLE t_session_cleanup_task");
        jdbcTemplate.update("TRUNCATE TABLE t_session_tombstone");
        jdbcTemplate.update("TRUNCATE TABLE t_sessions");
    }

    @Test
    void createsAndReadsAllRequiredSessionRows() {
        RuntimeSessionDTO session = newSession("session_db_create");

        repository.create(session);

        assertThat(repository.find(session.getId()).orElseThrow())
                .usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(session);
        assertThat(countSession(session.getId())).isOne();
        assertThat(count("t_session_sequences", session.getId())).isOne();
        assertThat(count("t_session_stats", session.getId())).isOne();
        assertThat(count("t_session_materialized", session.getId())).isOne();
    }

    @Test
    void shouldPersistFixedExecutionAndCloseExpectedSegmentAsConfirming() {
        RuntimeSessionDTO session = newSession("session_execution_segment");
        repository.create(session);
        RuntimeEntryDTO root = newEntry(session.getId(), "root-entry", "user.message", session.getCreatedAt(), "{}");
        repository.acceptUserEvent(session.getId(), root, session.getCreatedAt());
        var target = new ExecutionTargetDTO(session.getId(), "execution-1", "root-event", "segment-1");

        executionControls.register(target, root.getEntrySeq(), session.getCreatedAt());
        executionControls.linkCommittedEvent(target, "root-event", root.getEntrySeq());
        var status = executionControls.markConfirming(
                target,
                "tool-call-" + "x".repeat(300),
                "confirming-event",
                root.getEntrySeq() + 1,
                session.getCreatedAt().plusSeconds(1));

        assertThat(status).isEqualTo(RuntimeExecutionControlRepository.TransitionStatus.APPLIED);
        assertThat(executionControls.find(target).orElseThrow())
                .extracting("rootEventId", "state", "currentSegmentId", "terminalEventId")
                .containsExactly("root-event", RuntimeExecutionState.CONFIRMING, "segment-1", null);
        assertThat(repository.find(session.getId()).orElseThrow().getState()).isEqualTo("running");
        assertThat(count("t_session_execution_segment_events", session.getId())).isOne();
    }

    @Test
    void shouldRejectStaleAndRepeatedExecutionTerminalUpdates() {
        RuntimeSessionDTO session = newSession("session_execution_terminal");
        repository.create(session);
        RuntimeEntryDTO root = newEntry(session.getId(), "root-entry", "user.message", session.getCreatedAt(), "{}");
        repository.acceptUserEvent(session.getId(), root, session.getCreatedAt());
        var target = new ExecutionTargetDTO(session.getId(), "execution-1", "root-event", "segment-1");
        executionControls.register(target, root.getEntrySeq(), session.getCreatedAt());

        var stale = new ExecutionTargetDTO(session.getId(), "execution-1", "root-event", "old-segment");
        assertThat(executionControls.find(stale)).isEmpty();
        assertThat(executionControls.markTerminal(
                        stale,
                        "idle-stale",
                        root.getEntrySeq() + 1,
                        RuntimeExecutionTerminalReason.DONE,
                        session.getCreatedAt()))
                .isEqualTo(RuntimeExecutionControlRepository.TransitionStatus.STALE_TARGET);
        assertThat(executionControls.markTerminal(
                        target,
                        "idle-done",
                        root.getEntrySeq() + 1,
                        RuntimeExecutionTerminalReason.DONE,
                        session.getCreatedAt()))
                .isEqualTo(RuntimeExecutionControlRepository.TransitionStatus.APPLIED);
        assertThat(executionControls.markTerminal(
                        target,
                        "idle-duplicate",
                        root.getEntrySeq() + 2,
                        RuntimeExecutionTerminalReason.DONE,
                        session.getCreatedAt()))
                .isEqualTo(RuntimeExecutionControlRepository.TransitionStatus.STATE_CONFLICT);
        assertThat(executionControls.find(target).orElseThrow())
                .extracting("state", "terminalEventId", "terminalReason")
                .containsExactly(RuntimeExecutionState.TERMINAL, "idle-done", RuntimeExecutionTerminalReason.DONE);
    }

    @Test
    void shouldRestoreCurrentNameAfterContextRestartWithoutTouchingHistoryOrRunningState() {
        RuntimeSessionDTO session = newSession("session_name_restore");
        repository.create(session);
        OffsetDateTime now = session.getCreatedAt().plusMinutes(1);
        repository.acceptUserEvent(session.getId(), newEntry(session.getId(), "user", "user.message", now, "{}"), now);
        var before = repository.find(session.getId()).orElseThrow();
        var assembler = new RuntimeSessionResponseAssembler(new SessionEtagFactory());
        var service = new SessionNamingService(
                repository, Clock.fixed(now.plusSeconds(1).toInstant(), ZoneOffset.UTC));
        var result = service.execute(session.getId(), "  中文  name  ");
        assertThat(result.changed()).isTrue();
        var renamed = repository.find(session.getId()).orElseThrow();
        assertThat(result.session()).isEqualTo(renamed);
        assertThat(renamed.getDisplayName()).isEqualTo("中文  name");
        assertThat(renamed.getState()).isEqualTo("running");
        assertThat(renamed.getActiveLeafId()).isEqualTo(before.getActiveLeafId());
        assertThat(renamed.getResourceVersion()).isEqualTo(before.getResourceVersion() + 1);
        assertThat(assembler.getView(renamed).etag())
                .isNotEqualTo(assembler.getView(before).etag());
        assertThat(service.execute(session.getId(), "中文  name ").changed()).isFalse();
        assertThat(repository.find(session.getId())).contains(renamed);
        assertThat(count("t_session_entries", session.getId())).isOne();
        assertThat(count("t_session_records", session.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT next_seq FROM t_session_sequences WHERE session_id = ?", Long.class, session.getId()))
                .isEqualTo(2L);
        context.close();
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
        var restored = context.getBean(RuntimeSessionRepository.class)
                .find(session.getId())
                .orElseThrow();
        assertThat(restored).isEqualTo(renamed);
        assertThat(assembler.getView(restored).resource().getDisplayName()).isEqualTo("中文  name");
        assertThat(assembler.getView(restored).etag())
                .isEqualTo(assembler.getView(renamed).etag());
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "second"})
    void shouldCompareLatestNameUnderRowLockAndLetLastCommitWin(String nextName) throws Exception {
        RuntimeSessionDTO session = newSession("session_name_race");
        repository.create(session);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.execute(status -> {
                var result = repository.updateName(
                        session.getId(), "first", session.getUpdatedAt().plusSeconds(1));
                locked.countDown();
                awaitLatch(release);
                return result;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return repository.updateName(
                        session.getId(), nextName, session.getUpdatedAt().plusSeconds(2));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS).orElseThrow();
            var secondResult = second.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(firstResult.changed()).isTrue();
            assertThat(firstResult.session().getDisplayName()).isEqualTo("first");
            assertThat(firstResult.session().getResourceVersion()).isEqualTo(2L);
            assertThat(firstResult.session().getUpdatedAt())
                    .isEqualTo(session.getUpdatedAt().plusSeconds(1));
            assertThat(secondResult.changed()).isEqualTo(!nextName.equals("first"));
            var current = repository.find(session.getId()).orElseThrow();
            assertThat(secondResult.session()).isEqualTo(current);
            assertThat(current.getDisplayName()).isEqualTo(nextName);
            assertThat(current.getResourceVersion()).isEqualTo(nextName.equals("first") ? 2L : 3L);
            assertThat(count("t_session_entries", session.getId())).isZero();
            assertThat(current.getActiveLeafId()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldEnforceDatabaseByteLimitAndNotResurrectDeletedSessions() {
        RuntimeSessionDTO session = newSession("session_name_constraint");
        repository.create(session);
        assertThatThrownBy(() -> repository.updateName(session.getId(), "中".repeat(27), session.getUpdatedAt()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.find(session.getId()).orElseThrow())
                .usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(session);
        assertThat(repository.beginDeletion(session.getId(), session.getUpdatedAt()))
                .isEqualTo(SessionDeletionStatus.DELETED);
        assertThat(repository.updateName(session.getId(), "deleted", session.getUpdatedAt()))
                .isEmpty();
        assertThat(countSession(session.getId())).isZero();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "model", "thinking"})
    void shouldReturnExactlyStoredTimestampPrecisionForConfigurationChanges(String command) {
        var session = newSession("session_timestamp");
        repository.create(session);
        appendUsageForPrecisionTest(session);
        var storedUsage = repository.find(session.getId()).orElseThrow().getLifetimeUsage();
        assertThat(storedUsage.getInput()).isEqualTo(11L);
        assertThat(storedUsage.getCostTotal()).isEqualByComparingTo("0.5");
        var updatedAt = session.getUpdatedAt().plusSeconds(1).withNano(123_999_999);
        var changed = executeConfigurationCommand(
                command, session.getId(), Clock.fixed(updatedAt.toInstant(), ZoneOffset.UTC));
        assertThat(changed.changed()).isTrue();
        assertThat(changed.session()).isEqualTo(repository.find(session.getId()).orElseThrow());
        assertThat(changed.session().getUpdatedAt()).isEqualTo(updatedAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(changed.session().getResourceVersion()).isEqualTo(4L);
        assertThat(changed.session().getLifetimeUsage()).isEqualTo(storedUsage);
        var unchanged = executeConfigurationCommand(
                command, session.getId(), Clock.fixed(updatedAt.plusDays(1).toInstant(), ZoneOffset.UTC));
        assertThat(unchanged.changed()).isFalse();
        assertThat(unchanged.sourceEventSeq()).isNull();
        assertThat(unchanged.session()).isEqualTo(changed.session());
        assertThat(repository.find(session.getId())).contains(changed.session());
    }

    private void appendUsageForPrecisionTest(RuntimeSessionDTO session) {
        var now = session.getUpdatedAt();
        var codec = new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());
        var usage = new Usage(11, 7, 3, 2, 41, new Cost(0.1, 0.2, 0.03, 0.04, 0.5));
        repository.acceptUserEvent(
                session.getId(), newEntry(session.getId(), "usage-user", "user.message", now, "{}"), now);
        var entry = newEntry(session.getId(), "usage-entry", "assistant.message.completed", now, "{}");
        var record = codec.usageRecord(
                session.getId(),
                "usage-record",
                "usage-user",
                RuntimeUsageCause.ASSISTANT,
                entry.getId(),
                0,
                null,
                usage,
                now);
        repository.appendEntryWithUsage(entry, record, usage);
        repository.finishExecution(session.getId(), now);
    }

    private SessionCommandResultDTO executeConfigurationCommand(String command, String sessionId, Clock clock) {
        var snapshot = new AgentDirectorySnapshotDTO(
                "agent", "model-db-it", List.of("next"), Path.of("/runtime"), Path.of("/runtime/.campusclaw"));
        var manager = mock(RuntimeModelManager.class);
        var model = mock(Model.class);
        when(model.id()).thenReturn("next");
        when(model.reasoning()).thenReturn(true);
        when(manager.resolveAvailableModel(snapshot, "next")).thenReturn(model);
        when(manager.resolveModel(snapshot, "model-db-it")).thenReturn(model);
        var codec = new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());
        return switch (command) {
            case "name" -> new SessionNamingService(repository, clock).execute(sessionId, "name");
            case "model" ->
                (SessionCommandResultDTO) new SessionModelConfigurationService(
                                repository, agentId -> snapshot, manager, codec, () -> "model-entry", clock)
                        .execute(sessionId, "next");
            case "thinking" ->
                new SessionThinkingConfigurationService(
                                repository, agentId -> snapshot, manager, codec, () -> "thinking-entry", clock)
                        .execute(sessionId, "on");
            default -> throw new AssertionError(command);
        };
    }

    @Test
    void createsExactTombstoneAndPendingCleanupTaskWhenDeleting() {
        RuntimeSessionDTO session = newSession("session_db_delete");
        repository.create(session);
        seedClosedExecution(session);
        OffsetDateTime deletedAt = OffsetDateTime.of(2026, 8, 18, 1, 30, 0, 0, ZoneOffset.UTC);

        assertThat(repository.beginDeletion(session.getId(), deletedAt)).isEqualTo(SessionDeletionStatus.DELETED);

        assertThat(repository.find(session.getId())).isEmpty();
        assertThat(count("t_session_tombstone", session.getId())).isOne();
        assertThat(count("t_session_cleanup_task", session.getId())).isOne();
        assertThat(cleanupState(session.getId())).isEqualTo("PENDING");
        assertThat(tombstoneColumns()).containsExactly("session_id", "deleted_at");

        assertThat(repository.claimCleanupTask(deletedAt, deletedAt.minusMinutes(5)))
                .contains(session.getId());
        assertThat(cleanupState(session.getId())).isEqualTo("RUNNING");
        repository.completeCleanup(session.getId());

        assertThat(count("t_session_execution_segment_events", session.getId())).isZero();
        assertThat(count("t_session_execution_segments", session.getId())).isZero();
        assertThat(count("t_session_executions", session.getId())).isZero();
        assertThat(count("t_session_entries", session.getId())).isZero();
        assertThat(count("t_session_records", session.getId())).isZero();
        assertThat(count("t_session_stats", session.getId())).isZero();
        assertThat(count("t_session_sequences", session.getId())).isZero();
        assertThat(count("t_session_materialized", session.getId())).isZero();
        assertThat(count("t_session_cleanup_task", session.getId())).isZero();
        assertThat(count("t_session_tombstone", session.getId())).isOne();
    }

    @Test
    void reportsNotFoundWhenDeletingUnknownSession() {
        assertThat(repository.beginDeletion("session_missing", OffsetDateTime.now(ZoneOffset.UTC)))
                .isEqualTo(SessionDeletionStatus.NOT_FOUND);

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

        assertThat(repository.find(session.getId()).orElseThrow())
                .usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(session);
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

        UserEventAcceptance acceptance = repository.acceptUserEvent(session.getId(), user, acceptedAt);
        RuntimeEntryDTO thinking = newEntry(
                session.getId(),
                "entry_thinking",
                "assistant.thinking.completed",
                acceptedAt.plusSeconds(1),
                "{\"assistant_entry_id\":\"entry_assistant\",\"content_index\":0,"
                        + "\"content\":{\"type\":\"thinking\",\"text\":\"reasoning\"}}");
        repository.appendEntry(thinking);
        RuntimeEntryDTO assistant = newEntry(
                session.getId(),
                "entry_assistant",
                "assistant.message.completed",
                acceptedAt.plusSeconds(2),
                "{\"message\":{\"role\":\"assistant\",\"content\":[]},\"finish_reason\":\"stop\"}");
        repository.appendEntry(assistant);
        repository.finishExecution(session.getId(), acceptedAt.plusSeconds(3));

        assertThat(acceptance.status()).isEqualTo(Status.ACCEPTED);
        assertThat(user.getEntrySeq()).isEqualTo(1L);
        assertThat(user.getParentId()).isNull();
        assertThat(thinking.getEntrySeq()).isEqualTo(2L);
        assertThat(thinking.getParentId()).isEqualTo(user.getId());
        assertThat(assistant.getEntrySeq()).isEqualTo(3L);
        assertThat(assistant.getParentId()).isEqualTo(thinking.getId());
        assertThat(repository.listCurrentBranch(session.getId(), 0, 10, false))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("entry_user", "entry_assistant");
        assertThat(repository.listCurrentBranch(session.getId(), 0, 10, true))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("entry_user", "entry_thinking", "entry_assistant");
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

        assertThat(repository.listCurrentBranch(session.getId(), 0, 10, false))
                .extracting(RuntimeEntryDTO::getId)
                .containsExactly("entry_root", "entry_current");
    }

    @Test
    void modelChangeAtomicallyNormalizesThinkingAndAdvancesVersion() {
        RuntimeSessionDTO session = newSession("session_db_model_change");
        session.setThinking(true);
        repository.create(session);
        OffsetDateTime updatedAt = session.getCreatedAt().plusMinutes(1);

        SessionConfigurationUpdateDTO update =
                repository.updateModel(session.getId(), 1L, "model-next", false, locked -> List.of(), updatedAt);

        assertThat(update.status()).isEqualTo(SessionConfigurationUpdateDTO.Status.UPDATED);
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

        SessionConfigurationUpdateDTO update = repository.updateModel(
                session.getId(),
                1L,
                session.getModelId(),
                false,
                locked -> List.of(),
                session.getCreatedAt().plusHours(1));

        assertThat(update.status()).isEqualTo(SessionConfigurationUpdateDTO.Status.UNCHANGED);
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
                            SessionConfigurationUpdateDTO.Status.UPDATED,
                            SessionConfigurationUpdateDTO.Status.VERSION_MISMATCH);
        }

        assertThat(repository.find(session.getId()).orElseThrow().getResourceVersion())
                .isEqualTo(2L);
    }

    private int count(String table, String sessionId) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE session_id = ?", Integer.class, sessionId);
        return result == null ? 0 : result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "second"})
    void shouldSerializeUnconditionalModelChangesUsingLockedPreviousValue(String secondModel) throws Exception {
        var session = newSession("session_model_command_race");
        repository.create(session);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> changeUnconditionally(start, session, "first"));
            var second = executor.submit(() -> changeUnconditionally(start, session, secondModel));
            start.countDown();
            var updates = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            var events = repository.listCurrentBranch(session.getId(), 0L, 10, true);
            int expectedChanges = secondModel.equals("first") ? 1 : 2;
            assertThat(events).hasSize(expectedChanges);
            assertThat(updates.stream()
                            .filter(update -> update.status() == SessionConfigurationUpdateDTO.Status.UPDATED))
                    .hasSize(expectedChanges);
            assertThat(updates.stream()
                            .map(SessionConfigurationUpdateDTO::sourceEventSeq)
                            .filter(java.util.Objects::nonNull))
                    .containsExactlyInAnyOrderElementsOf(
                            events.stream().map(RuntimeEntryDTO::getEntrySeq).toList());
            assertThat(events.getFirst().getPayload()).contains("model-db-it");
            if (expectedChanges == 2) {
                assertThat(events.getLast().getParentId())
                        .isEqualTo(events.getFirst().getId());
                assertThat(events.getLast().getPayload())
                        .contains(events.getFirst().getId());
            }
            assertThat(repository.find(session.getId()).orElseThrow().getResourceVersion())
                    .isEqualTo(1L + expectedChanges);
        }
    }

    @Test
    void shouldRollbackModelAndSequenceWhenEventInsertionFails() throws Exception {
        var session = newSession("session_model_command_rollback");
        repository.create(session);
        assertThatThrownBy(() -> repository.updateModel(
                        session.getId(),
                        null,
                        "next",
                        false,
                        locked -> List.of(
                                newEntry(
                                        locked.getId(),
                                        "duplicate",
                                        "session.model.changed",
                                        session.getCreatedAt(),
                                        "{}"),
                                newEntry(
                                        locked.getId(),
                                        "duplicate",
                                        "session.thinking.changed",
                                        session.getCreatedAt(),
                                        "{}")),
                        session.getCreatedAt().plusMinutes(1)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.find(session.getId()).orElseThrow())
                .usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(session);
        assertThat(repository.listCurrentBranch(session.getId(), 0L, 10, true)).isEmpty();
        assertThat(changeUnconditionally(new CountDownLatch(0), session, "next").sourceEventSeq())
                .isEqualTo(1L);
    }

    private SessionConfigurationUpdateDTO changeUnconditionally(
            CountDownLatch start, RuntimeSessionDTO observed, String modelId) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository.updateModel(
                observed.getId(),
                null,
                modelId,
                true,
                locked -> List.of(newEntry(
                        locked.getId(),
                        modelId,
                        "session.model.changed",
                        observed.getCreatedAt(),
                        "{\"previousModelId\":\"" + locked.getModelId() + "\",\"modelId\":\"" + modelId + "\"}")),
                observed.getCreatedAt().plusMinutes(1));
    }

    private int countSession(String sessionId) {
        Integer result =
                jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_sessions WHERE id = ?", Integer.class, sessionId);
        return result == null ? 0 : result;
    }

    @Test
    void shouldSerializeSameThinkingCommandsAndRestoreWithoutNewEvent() throws Exception {
        var session = newSession("session_thinking_commands");
        repository.create(session);
        var service = thinkingService(new CountDownLatch(0));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                awaitLatch(start);
                return service.execute(session.getId(), "on");
            });
            var second = executor.submit(() -> {
                awaitLatch(start);
                return service.execute(session.getId(), "on");
            });
            start.countDown();
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results).extracting(SessionCommandResultDTO::changed).containsExactlyInAnyOrder(true, false);
            assertThat(results)
                    .extracting(SessionCommandResultDTO::sourceEventSeq)
                    .containsExactlyInAnyOrder(1L, null);
            var current = repository.find(session.getId()).orElseThrow();
            assertThat(results).extracting(SessionCommandResultDTO::session).containsOnly(current);
        }
        assertThat(repository.find(session.getId()).orElseThrow().getResourceVersion())
                .isEqualTo(2L);
        assertThat(repository.listCurrentBranch(session.getId(), 0L, 10, true))
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("session.thinking.changed");
        context.close();
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
        repository = context.getBean(RuntimeSessionRepository.class);
        var restored = thinkingService(new CountDownLatch(0)).execute(session.getId(), "");
        assertThat(restored.session())
                .isEqualTo(repository.find(session.getId()).orElseThrow());
        assertThat(restored.session().isThinking()).isTrue();
        assertThat(restored.changed()).isFalse();
        assertThat(restored.sourceEventSeq()).isNull();
        assertThat(repository.listCurrentBranch(session.getId(), 0L, 10, true)).hasSize(1);
    }

    @Test
    void shouldRejectThinkingWhenConcurrentModelCommitRemovesCapability() throws Exception {
        var session = newSession("session_thinking_model_race");
        repository.create(session);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var capabilityChecked = new CountDownLatch(1);
        var service = thinkingService(capabilityChecked);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var model = executor.submit(() -> transaction.execute(status -> {
                var update = repository.updateModel(
                        session.getId(),
                        null,
                        "unsupported",
                        false,
                        current -> List.of(newEntry(
                                current.getId(), "model-entry", "session.model.changed", session.getCreatedAt(), "{}")),
                        session.getCreatedAt());
                locked.countDown();
                awaitLatch(release);
                return update;
            }));
            awaitLatch(locked);
            var thinking = executor.submit(() -> service.execute(session.getId(), "on"));
            awaitLatch(capabilityChecked);
            assertThatThrownBy(() -> thinking.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(model.get(5, TimeUnit.SECONDS).sourceEventSeq()).isEqualTo(1L);
            assertThatThrownBy(() -> thinking.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RuntimeApiException.class)
                    .satisfies(error -> assertThat(((RuntimeApiException) error.getCause()).errorCode())
                            .isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED));
            var stored = repository.find(session.getId()).orElseThrow();
            assertThat(stored.getModelId()).isEqualTo("unsupported");
            assertThat(stored.isThinking()).isFalse();
            assertThat(stored.getResourceVersion()).isEqualTo(2L);
            assertThat(repository.listCurrentBranch(session.getId(), 0L, 10, true))
                    .hasSize(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRollbackThinkingVersionLeafAndSequenceWhenEntryFails() throws Exception {
        var session = newSession("session_thinking_rollback");
        repository.create(session);
        var service = thinkingService(new CountDownLatch(0));
        assertThat(service.execute(session.getId(), "on").sourceEventSeq()).isEqualTo(1L);
        var before = repository.find(session.getId()).orElseThrow();
        assertThatThrownBy(() -> service.execute(session.getId(), "off"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.COMMAND_EXECUTION_FAILED));
        assertThat(repository.find(session.getId())).contains(before);
        assertThat(repository.listCurrentBranch(session.getId(), 0L, 10, true))
                .extracting(RuntimeEntryDTO::getEntrySeq)
                .containsExactly(1L);
        assertThat(changeUnconditionally(new CountDownLatch(0), session, "next").sourceEventSeq())
                .isEqualTo(2L);
    }

    private SessionThinkingConfigurationService thinkingService(CountDownLatch capabilityChecked) {
        var snapshot = new AgentDirectorySnapshotDTO(
                "agent",
                "model-db-it",
                List.of("model-db-it", "unsupported"),
                Path.of("/runtime/agent"),
                Path.of("/runtime/agent/.campusclaw"));
        var manager = mock(RuntimeModelManager.class);
        var capable = mock(Model.class);
        when(capable.reasoning()).thenReturn(true);
        when(manager.resolveModel(snapshot, "model-db-it")).thenAnswer(call -> {
            capabilityChecked.countDown();
            return capable;
        });
        when(manager.resolveModel(snapshot, "unsupported")).thenReturn(mock(Model.class));
        return new SessionThinkingConfigurationService(
                repository,
                agentId -> snapshot,
                manager,
                new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource()),
                () -> "entry-thinking",
                Clock.systemUTC());
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
                .acceptUserEvent(session.getId(), entry, entry.getTimestamp())
                .status();
    }

    private SessionConfigurationUpdateDTO.Status updateModelAfterLatch(CountDownLatch start, RuntimeSessionDTO session)
            throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository
                .updateModel(
                        session.getId(),
                        1L,
                        "model-race",
                        true,
                        locked -> List.of(newEntry(
                                session.getId(),
                                "entry-model-race",
                                "session.model.changed",
                                session.getUpdatedAt().plusMinutes(1),
                                "{}")),
                        session.getUpdatedAt().plusMinutes(1))
                .status();
    }

    private SessionConfigurationUpdateDTO.Status updateThinkingAfterLatch(
            CountDownLatch start, RuntimeSessionDTO session) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository
                .updateThinking(
                        session.getId(),
                        1L,
                        true,
                        locked -> assertThat(locked.getModelId()).isEqualTo("model-db-it"),
                        locked -> newEntry(
                                session.getId(),
                                "entry-thinking-race",
                                "session.thinking.changed",
                                session.getUpdatedAt().plusMinutes(1),
                                "{}"),
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

    private void seedClosedExecution(RuntimeSessionDTO session) {
        RuntimeEntryDTO root =
                newEntry(session.getId(), "delete-root-entry", "user.message", session.getCreatedAt(), "{}");
        repository.acceptUserEvent(session.getId(), root, root.getTimestamp());
        var target = new ExecutionTargetDTO(session.getId(), "delete-execution", "delete-root-event", "delete-segment");
        executionControls.register(target, root.getEntrySeq(), root.getTimestamp());
        executionControls.linkCommittedEvent(target, target.rootEventId(), root.getEntrySeq());
        executionControls.linkCommittedEvent(target, "delete-idle-event", root.getEntrySeq() + 1);
        assertThat(executionControls.markTerminal(
                        target,
                        "delete-idle-event",
                        root.getEntrySeq() + 1,
                        RuntimeExecutionTerminalReason.DONE,
                        root.getTimestamp().plusSeconds(1)))
                .isEqualTo(RuntimeExecutionControlRepository.TransitionStatus.APPLIED);
        repository.finishExecution(session.getId(), root.getTimestamp().plusSeconds(1));
    }

    private static RuntimeSessionDTO newSession(String sessionId) {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 18, 1, 0, 0, 0, ZoneOffset.UTC);
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(sessionId);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
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
     * @version [br_eCampusCore 26.0.0, 2026/08/18]
     * @since [br_eCampusCore 26.0.0]
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
                    .getResources("classpath*:mapper/session/Runtime*Mapper.xml"));
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
        RuntimeExecutionControlRepository runtimeExecutionControlRepository(
                RuntimeExecutionControlMapper mapper, RuntimeSessionMapper sessionMapper) {
            return new MyBatisRuntimeExecutionControlRepository(mapper, sessionMapper);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
