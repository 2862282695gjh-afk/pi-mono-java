/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeUsageCause;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
 * 使用真实 openGauss 验证 Usage 分项原子累计、锁内快照、失败回滚和跨 JVM 恢复。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeLifetimeUsageOpenGaussIT {
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
        session.setId("usage-it-" + UUID.randomUUID());
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

    @Test
    void shouldReadCompleteZeroUsageWithoutChangingSessionOrHistory() {
        var before = persistentState();
        RuntimeSessionDTO read = repository.find(session.getId()).orElseThrow();
        JsonNode json = sessionJson(read);
        assertThat(json.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "sessionId",
                        "agentId",
                        "displayName",
                        "modelId",
                        "state",
                        "thinking",
                        "lifetimeUsage",
                        "createdAt",
                        "updatedAt");
        assertThat(json.get("displayName").isNull()).isTrue();
        assertThat(json.get("lifetimeUsage").properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("input", "output", "cacheRead", "cacheWrite", "totalTokens", "cost");
        assertThat(json.get("lifetimeUsage").get("cost").properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("input", "output", "cacheRead", "cacheWrite", "total");
        assertThat(read.getLifetimeUsage())
                .usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .isEqualTo(new RuntimeLifetimeUsageDTO());
        assertThat(persistentState()).isEqualTo(before);
    }

    @Test
    void shouldAccumulateRealAssistantAndCompactionPartsWithoutReconstructingTotals() throws Exception {
        startRunning();
        Usage value = usage();
        append("assistant", value, RuntimeUsageCause.ASSISTANT);
        append("compaction", value, RuntimeUsageCause.COMPACTION);
        RuntimeSessionDTO read = repository.find(session.getId()).orElseThrow();
        assertUsageTwice(read.getLifetimeUsage());
        assertThat(read.getResourceVersion()).isEqualTo(2L);
        assertThat(read.getUpdatedAt()).isEqualTo(now);
        var stored = jdbc.queryForList(
                "SELECT payload::text FROM t_session_records WHERE session_id=? ORDER BY record_seq",
                String.class,
                session.getId());
        assertThat(stored).hasSize(2);
        for (String payload : stored) {
            assertThat(new ObjectMapper().readTree(payload).get("usage"))
                    .isEqualTo(new ObjectMapper().valueToTree(value));
        }
        assertThat(jdbc.queryForMap(
                        "SELECT cached_tokens,uncached_tokens,message_count FROM t_session_stats WHERE session_id=?",
                        session.getId()))
                .containsEntry("cached_tokens", 6L)
                .containsEntry("uncached_tokens", 4_294_967_308L)
                .containsEntry("message_count", 2L);
        assertThat(jdbc.queryForObject(
                        "SELECT next_seq FROM t_session_sequences WHERE session_id=?", Long.class, session.getId()))
                .isEqualTo(6L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveZeroAndMissingCostUsage(boolean missingUsage) {
        startRunning();
        append("empty", missingUsage ? null : new Usage(1, 2, 3, 4, 19, null), RuntimeUsageCause.ASSISTANT);
        var read = repository.find(session.getId()).orElseThrow().getLifetimeUsage();
        assertThat(read)
                .extracting("input", "output", "cacheRead", "cacheWrite", "totalTokens")
                .containsExactly(
                        missingUsage ? 0L : 1L,
                        missingUsage ? 0L : 2L,
                        missingUsage ? 0L : 3L,
                        missingUsage ? 0L : 4L,
                        missingUsage ? 0L : 19L);
        assertThat(read)
                .extracting("costInput", "costOutput", "costCacheRead", "costCacheWrite", "costTotal")
                .allSatisfy(value -> assertThat((java.math.BigDecimal) value).isZero());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateRecord", "negativeTokens", "negativeCost"})
    void shouldRollbackEntryRecordSequenceLeafAndEveryStatWhenAnyWriteFails(String failure) {
        startRunning();
        append("first", usage(), RuntimeUsageCause.ASSISTANT);
        var before = persistentState();
        Usage rejected =
                switch (failure) {
                    case "negativeTokens" -> new Usage(Integer.MIN_VALUE, 0, 0, 0, 0, Cost.empty());
                    case "negativeCost" -> new Usage(0, 0, 0, 0, 0, new Cost(-1, 0, 0, 0, 0));
                    default -> usage();
                };
        String recordId = failure.equals("duplicateRecord") ? "record-first" : "record-invalid";
        assertThatThrownBy(() -> appendWithRecordId("invalid", recordId, rejected, RuntimeUsageCause.ASSISTANT))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(persistentState()).isEqualTo(before);
    }

    @Test
    void shouldSerializeConcurrentWritersWithoutLosingAnyParts() throws Exception {
        startRunning();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> appendAfter(start, "first"));
            var second = workers.submit(() -> appendAfter(start, "second"));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertUsageTwice(repository.find(session.getId()).orElseThrow().getLifetimeUsage());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_session_records WHERE session_id=?", Long.class, session.getId()))
                .isEqualTo(2L);
    }

    @Test
    void shouldReadUsageAfterLockWaitAndKeepUnchangedConfigurationVersion() throws Exception {
        startRunning();
        var started = new CountDownLatch(1);
        var pending = new AtomicReference<java.util.concurrent.Future<SessionConfigurationUpdateDTO>>();
        try (var workers = Executors.newSingleThreadExecutor();
                var reader = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class)) {
            transaction.executeWithoutResult(status -> {
                append("committing", usage(), RuntimeUsageCause.ASSISTANT);
                repository.finishExecution(session.getId(), now.plusSeconds(1));
                RuntimeSessionDTO uncommitted = reader.getBean(RuntimeSessionRepository.class)
                        .find(session.getId())
                        .orElseThrow();
                assertThat(uncommitted.getState()).isEqualTo("running");
                assertThat(uncommitted.getLifetimeUsage().getInput()).isZero();
                pending.set(workers.submit(() -> {
                    started.countDown();
                    return repository.updateModel(
                            session.getId(), null, "model", true, ignored -> List.of(), now.plusSeconds(2));
                }));
                assertThat(startedAwait(started)).isTrue();
                assertThatThrownBy(() -> pending.get().get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            });
            var unchanged = pending.get().get(10, TimeUnit.SECONDS);
            assertThat(unchanged.status()).isEqualTo(SessionConfigurationUpdateDTO.Status.UNCHANGED);
            assertThat(unchanged.session().getLifetimeUsage().getInput()).isEqualTo(Integer.MAX_VALUE);
            assertThat(unchanged.session().getResourceVersion()).isEqualTo(3L);
            assertThat(unchanged.session().getUpdatedAt()).isEqualTo(now.plusSeconds(1));
            assertThat(unchanged.session())
                    .isEqualTo(repository.find(session.getId()).orElseThrow());
        }
    }

    @Test
    void shouldRestoreSameSessionResourceInAnotherJvm() throws Exception {
        startRunning();
        append("saved", usage(), RuntimeUsageCause.COMPACTION);
        repository.finishExecution(session.getId(), now.plusSeconds(1));
        JsonNode expected = sessionJson(repository.find(session.getId()).orElseThrow());
        Path output = temporary.resolve("restored.json");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ProcessReader.class.getName(),
                session.getId(),
                output.toString());
        for (String key : List.of("url", "username", "password")) {
            builder.environment().put("USAGE_IT_" + key, System.getProperty("gaussdb.it." + key));
        }
        Process child = builder.redirectErrorStream(true)
                .redirectOutput(temporary.resolve("child.log").toFile())
                .start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(expected.toString());
        } finally {
            child.destroyForcibly();
        }
    }

    private void startRunning() {
        repository.acceptUserEvent(session.getId(), entry("user", "user.message"), now);
    }

    private void appendAfter(CountDownLatch start, String id) {
        assertThat(startedAwait(start)).isTrue();
        append(id, usage(), RuntimeUsageCause.ASSISTANT);
    }

    private static boolean startedAwait(CountDownLatch started) {
        try {
            return started.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("usage test interrupted", error);
        }
    }

    private void append(String id, Usage usage, RuntimeUsageCause cause) {
        appendWithRecordId(id, "record-" + id, usage, cause);
    }

    private void appendWithRecordId(String id, String recordId, Usage usage, RuntimeUsageCause cause) {
        var entry = entry(
                id,
                cause == RuntimeUsageCause.COMPACTION ? "session.compaction.completed" : "assistant.message.completed");
        var record = codec.usageRecord(session.getId(), recordId, "user", cause, id, 0, null, usage, now);
        repository.appendEntryWithUsage(entry, record, usage);
    }

    private RuntimeEntryDTO entry(String id, String type) {
        var entry = new RuntimeEntryDTO();
        entry.setSessionId(session.getId());
        entry.setId(id);
        entry.setType(type);
        entry.setTimestamp(now);
        entry.setPayload("{}");
        return entry;
    }

    private List<Object> persistentState() {
        return List.of("t_sessions", "t_session_stats", "t_session_entries", "t_session_records", "t_session_sequences")
                .stream()
                .map(table -> (Object) jdbc.queryForList(
                        "SELECT * FROM " + table + " WHERE " + (table.equals("t_sessions") ? "id" : "session_id")
                                + "=?",
                        session.getId()))
                .toList();
    }

    private static Usage usage() {
        return new Usage(Integer.MAX_VALUE, 5, 3, 7, 101, new Cost(0.03, 0.007, 0.00002, 0.000003, 0.12345678));
    }

    private static void assertUsageTwice(RuntimeLifetimeUsageDTO usage) {
        assertThat(usage)
                .extracting("input", "output", "cacheRead", "cacheWrite", "totalTokens")
                .containsExactly(4_294_967_294L, 10L, 6L, 14L, 202L);
        assertThat(usage.getCostInput()).isEqualByComparingTo("0.06");
        assertThat(usage.getCostOutput()).isEqualByComparingTo("0.014");
        assertThat(usage.getCostCacheRead()).isEqualByComparingTo("0.00004");
        assertThat(usage.getCostCacheWrite()).isEqualByComparingTo("0.000006");
        assertThat(usage.getCostTotal()).isEqualByComparingTo("0.24691356");
    }

    private static ObjectMapper jsonMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private static JsonNode sessionJson(RuntimeSessionDTO session) {
        return jsonMapper()
                .valueToTree(new RuntimeSessionResponseAssembler(new SessionEtagFactory())
                        .getView(session)
                        .resource());
    }

    /**
     * 使用独立 JVM 和 Spring 上下文读取已提交 Session 资源。
     *
     * @version [br_eCampusCore 26.0.0, 2026/09/07]
     * @since [br_eCampusCore 26.0.0]
     */
    public static class ProcessReader {
        public static void main(String[] arguments) throws Exception {
            for (String key : List.of("url", "username", "password")) {
                System.setProperty("gaussdb.it." + key, System.getenv("USAGE_IT_" + key));
            }
            try (var restored = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class)) {
                var session = restored.getBean(RuntimeSessionRepository.class)
                        .find(arguments[0])
                        .orElseThrow();
                Files.writeString(Path.of(arguments[1]), sessionJson(session).toString(), StandardCharsets.UTF_8);
            }
        }
    }
}
