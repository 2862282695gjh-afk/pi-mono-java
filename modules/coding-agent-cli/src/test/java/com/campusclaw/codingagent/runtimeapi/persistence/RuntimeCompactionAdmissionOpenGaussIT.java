/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在专用真实 openGauss 数据库验证压缩观察、准入回滚及跨 JVM 行锁竞争，不调用外部模型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCompactionAdmissionOpenGaussIT {
    @TempDir
    Path temporary;

    private AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository repository;

    private JdbcTemplate jdbc;

    private TransactionTemplate transaction;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final RuntimeEntryCodec codec =
            new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    @BeforeEach
    void createIsolatedSession() {
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
        repository = context.getBean(RuntimeSessionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        session.setId("compact-it-" + UUID.randomUUID());
        session.setAgentId("agent");
        session.setModelId("model");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setCwd(temporary.toString());
        repository.create(session);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldObserveEmptyContextWithoutMutatingPersistentData(boolean configurationOnly) {
        if (configurationOnly) {
            repository.updateThinking(
                    session.getId(), null, true, ignored -> {}, ignored -> entry("session.thinking.changed"), now);
        }
        List<Object> before = persistentState();
        var observed = repository.observeCompaction(session.getId()).orElseThrow();
        assertThat(observed.entries()).hasSize(configurationOnly ? 1 : 0);
        assertThat(codec.toAgentContextEntryIds(observed.entries())).isEmpty();
        assertThat(persistentState()).isEqualTo(before);
    }

    @Test
    void shouldPreserveConcurrentNameAndHistoryWhileAdmittingWithoutAnEntry() {
        seedHistory();
        var observed = repository.observeCompaction(session.getId()).orElseThrow();
        repository.updateName(session.getId(), "并发名称", now.plusSeconds(1));
        var before = persistentState();
        long version = repository.find(session.getId()).orElseThrow().getResourceVersion();
        assertThat(repository.acceptCompaction(observed.session(), now.plusSeconds(2)))
                .isEqualTo(CompactionAcceptanceStatus.ACCEPTED);
        RuntimeSessionDTO current = repository.find(session.getId()).orElseThrow();
        assertThat(current.getState()).isEqualTo("running");
        assertThat(current.getDisplayName()).isEqualTo("并发名称");
        assertThat(current.getResourceVersion()).isEqualTo(version + 1);
        assertThat(current.getActiveLeafId()).isEqualTo(observed.session().getActiveLeafId());
        assertThat(persistentState().subList(1, before.size())).isEqualTo(before.subList(1, before.size()));
        assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                .isEqualTo(observed.entries());
        assertThat(repository.observeCompaction(session.getId()).orElseThrow().entries())
                .isEmpty();
        assertThat(repository.acceptCompaction(observed.session(), now)).isEqualTo(CompactionAcceptanceStatus.BUSY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"history", "model", "thinking", "deleted"})
    void shouldRejectStaleObservationWithoutMutatingCurrentState(String change) {
        seedHistory();
        var observed = repository.observeCompaction(session.getId()).orElseThrow();
        switch (change) {
            case "history" -> seedHistory();
            case "model" ->
                repository.updateModel(
                        session.getId(), null, "other", true, ignored -> List.of(entry("session.model.changed")), now);
            case "thinking" ->
                repository.updateThinking(
                        session.getId(), null, true, ignored -> {}, ignored -> entry("session.thinking.changed"), now);
            case "deleted" -> repository.beginDeletion(session.getId(), now);
            default -> throw new AssertionError(change);
        }
        var current = persistentState();
        assertThat(repository.acceptCompaction(observed.session(), now))
                .isEqualTo(
                        change.equals("deleted")
                                ? CompactionAcceptanceStatus.NOT_FOUND
                                : CompactionAcceptanceStatus.BUSY);
        assertThat(persistentState()).isEqualTo(current);
        if (change.equals("deleted")) {
            assertThat(repository.observeCompaction(session.getId())).isEmpty();
        }
    }

    @Test
    void shouldRollbackAdmissionAndReadEveryPageWithoutMutation() {
        jdbc.update(
                "INSERT INTO t_session_entries(session_id,id,entry_seq,parent_id,type,timestamp,payload) "
                        + "SELECT ?, 'entry-' || n, n, CASE WHEN n=1 THEN NULL ELSE 'entry-' || (n-1) END, "
                        + "'user.message', ?, '{}'::jsonb FROM generate_series(1,501) n",
                session.getId(),
                now);
        jdbc.update("UPDATE t_sessions SET active_leaf_id='entry-501' WHERE id=?", session.getId());
        var before = persistentState();
        var observed = repository.observeCompaction(session.getId()).orElseThrow();
        assertThat(observed.entries()).hasSize(501);
        assertThat(observed.entries()).extracting(RuntimeEntryDTO::getEntrySeq).isSorted();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    assertThat(repository.acceptCompaction(observed.session(), now))
                            .isEqualTo(CompactionAcceptanceStatus.ACCEPTED);
                    throw new IllegalStateException("rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");
        assertThat(persistentState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compact", "event", "observe"})
    void shouldSerializeCompactionAgainstAnotherJvm(String contender) throws Exception {
        seedHistory();
        Path ready = temporary.resolve("ready");
        Path result = temporary.resolve("result");
        var childReference = new AtomicReference<Process>();
        try {
            transaction.executeWithoutResult(status -> {
                if (contender.equals("compact")) {
                    var observed = repository.observeCompaction(session.getId()).orElseThrow();
                    assertThat(repository.acceptCompaction(observed.session(), now))
                            .isEqualTo(CompactionAcceptanceStatus.ACCEPTED);
                } else {
                    assertThat(repository
                                    .acceptUserEvent(session.getId(), entry("user.message"), now)
                                    .status())
                            .isEqualTo(UserEventAcceptance.Status.ACCEPTED);
                }
                Process child = startChild(contender, ready, result);
                childReference.set(child);
                awaitReady(child, ready);
                assertThatThrownBy(() -> child.onExit().get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            });
            Process child = childReference.get();
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(Files.readString(result, StandardCharsets.UTF_8))
                    .isEqualTo(contender.equals("observe") ? "running:0" : "BUSY");
            assertThat(repository.listCurrentBranchEntries(session.getId(), 0L, 500))
                    .hasSize(contender.equals("compact") ? 1 : 2);
        } finally {
            if (childReference.get() != null) {
                childReference.get().destroyForcibly();
            }
        }
    }

    private Process startChild(String contender, Path ready, Path result) {
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ProcessClient.class.getName(),
                session.getId(),
                contender,
                ready.toString(),
                result.toString());
        for (String key : List.of("url", "username", "password")) {
            builder.environment().put("COMPACT_IT_" + key, System.getProperty("gaussdb.it." + key));
        }
        try {
            return builder.redirectErrorStream(true)
                    .redirectOutput(temporary.resolve("child.log").toFile())
                    .start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void awaitReady(Process child, Path ready) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(Files.exists(ready)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private void seedHistory() {
        repository.acceptUserEvent(session.getId(), entry("user.message"), now);
        repository.finishExecution(session.getId(), now);
    }

    private RuntimeEntryDTO entry(String type) {
        var entry = codec.userEntry(session.getId(), UUID.randomUUID().toString(), "history", List.of(), now);
        entry.setType(type);
        return entry;
    }

    private List<Object> persistentState() {
        return List.of(
                repository.find(session.getId()),
                jdbc.queryForList(
                        "SELECT * FROM t_session_entries WHERE session_id=? ORDER BY entry_seq", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_sequences WHERE session_id=?", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_stats WHERE session_id=?", session.getId()),
                jdbc.queryForList("SELECT * FROM t_session_records WHERE session_id=?", session.getId()));
    }

    /**
     * 独立 JVM 竞争者，仅通过测试临时文件通信，连接凭据从子进程环境读取。
     *
     * @version [br_eCampusCore 26.0.0, 2026/09/07]
     * @since [br_eCampusCore 26.0.0]
     */
    public static final class ProcessClient {
        public static void main(String[] args) throws Exception {
            for (String key : List.of("url", "username", "password")) {
                System.setProperty("gaussdb.it." + key, System.getenv("COMPACT_IT_" + key));
            }
            try (var childContext = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class)) {
                var childRepository = childContext.getBean(RuntimeSessionRepository.class);
                RuntimeSessionDTO observed = childRepository.find(args[0]).orElseThrow();
                Files.writeString(Path.of(args[2]), "ready", StandardCharsets.UTF_8);
                if (args[1].equals("observe")) {
                    var snapshot = childRepository.observeCompaction(args[0]).orElseThrow();
                    Files.writeString(
                            Path.of(args[3]),
                            snapshot.session().getState() + ":"
                                    + snapshot.entries().size(),
                            StandardCharsets.UTF_8);
                } else {
                    Files.writeString(
                            Path.of(args[3]),
                            childRepository
                                    .acceptCompaction(observed, OffsetDateTime.now())
                                    .name(),
                            StandardCharsets.UTF_8);
                }
            }
        }
    }
}
