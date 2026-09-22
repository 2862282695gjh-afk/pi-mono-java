/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 openGauss 验证公共事件单条时间线分页查询。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class CommittedEventQueryRepositoryOpenGaussIT {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 8, 1, 0, 0, 0, ZoneOffset.UTC);

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
        RuntimeOpenGaussTestCleaner.truncate(jdbcTemplate);
    }

    @Test
    void shouldReadTimelineEventsWithNumericOffsetAndMultipleEvents() {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-root", 1L, null);
        insertEntry(session.getId(), "entry-second", 5L, "entry-root");
        insertEntry(session.getId(), "entry-private", 9L, "entry-second", "session.compaction.started");
        insertEvent(session.getId(), "event-root", 2L, "entry-root");
        insertEvent(session.getId(), "event-root-followup", 3L, "entry-root");
        insertEvent(session.getId(), "event-second", 7L, "entry-second");
        insertProjection(session.getId(), "entry-root", 2);
        insertProjection(session.getId(), "entry-second", 1);
        insertProjection(session.getId(), "entry-private", 0);
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-private", session.getId());

        var firstPage = repository.findEventPage(session.getId(), 0L, 2).orElseThrow();
        var secondPage = repository.findEventPage(session.getId(), 2L, 2).orElseThrow();

        assertThat(firstPage)
                .extracting(CommittedEventDTO::getEventId)
                .containsExactly("event-root", "event-root-followup");
        assertThat(secondPage).extracting(CommittedEventDTO::getEventId).containsExactly("event-second");
        assertThat(repository.findEventPage("session-missing", 0L, 1)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 1000, 10000})
    void shouldReadLongTimelineWithoutRecursiveDepthLimit(int entryCount) {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertLongTimeline(session.getId(), entryCount);

        long upperBound = repository.findTimelineUpperBound(session.getId());
        var entries = repository.listTimelineEntries(session.getId(), 0L, upperBound, entryCount + 1);
        var tail = repository.findEventPage(session.getId(), entryCount - 2L, 3).orElseThrow();

        assertThat(entries).hasSize(entryCount);
        assertThat(entries.getLast().getEntrySeq()).isEqualTo(entryCount * 3L - 2L);
        assertThat(tail)
                .extracting(CommittedEventDTO::getEventId)
                .containsExactly("event-" + (entryCount - 1), "event-" + entryCount);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "user.message",
                "assistant.thinking.completed",
                "future.unknown",
                "leaf",
                "branch_summary",
                "label"
            })
    void shouldFailClosedWhenTimelineMappingIsUnsafe(String type) {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-unmapped", 1L, null, type);
        if (!"user.message".equals(type) && !"assistant.thinking.completed".equals(type)) {
            insertProjection(session.getId(), "entry-unmapped", 0);
        }
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-unmapped", session.getId());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> repository.findEventPage(session.getId(), 0L, 1));

        assertThat(error).hasMessage("session timeline event mapping is incomplete");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "assistant.message.started",
                "assistant.message.delta",
                "assistant.thinking.started",
                "assistant.thinking.delta",
                "tool.execution.delta",
                "tool.execution.completed",
                "stream.end",
                "stream.error"
            })
    void shouldRejectRetiredEntryTypeEvenWithZeroEventProjection(String type) {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-retired", 1L, null, type);
        insertProjection(session.getId(), "entry-retired", 0);
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-retired", session.getId());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> repository.findEventPage(session.getId(), 0L, 1));

        assertThat(error).hasMessage("session timeline event mapping is incomplete");
    }

    @Test
    void shouldRejectPartiallyMappedAssistantEntry() {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-assistant", 1L, null, "assistant.message.completed");
        insertEvent(session.getId(), "event-message", 2L, "entry-assistant");
        insertProjection(session.getId(), "entry-assistant", 2);
        jdbcTemplate.update(
                "UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-assistant", session.getId());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> repository.findEventPage(session.getId(), 0L, 10));

        assertThat(error).hasMessage("session timeline event mapping is incomplete");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldAcceptReviewedThinkingVisibility(int eventCount) {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-thinking", 1L, null, "assistant.thinking.completed");
        if (eventCount == 1) {
            insertEvent(session.getId(), "event-thinking", 2L, "entry-thinking");
        }
        insertProjection(session.getId(), "entry-thinking", eventCount);
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-thinking", session.getId());

        var events = repository.findEventPage(session.getId(), 0L, 10).orElseThrow();

        assertThat(events).hasSize(eventCount);
    }

    @Test
    void shouldRejectToolStartWithoutPublicToolCall() {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-tool-start", 1L, null, "tool.execution.started");
        insertProjection(session.getId(), "entry-tool-start", 0);
        jdbcTemplate.update(
                "UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-tool-start", session.getId());

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> repository.findEventPage(session.getId(), 0L, 10));

        assertThat(error).hasMessage("session timeline event mapping is incomplete");
    }

    private void insertLongTimeline(String sessionId, int entryCount) {
        jdbcTemplate.update(
                "INSERT INTO t_session_entries "
                        + "(session_id,id,entry_seq,parent_id,type,timestamp,payload) "
                        + "SELECT ?, 'entry-' || n, n * 3 - 2, "
                        + "CASE WHEN n = 1 THEN NULL ELSE 'entry-' || (n - 1) END, "
                        + "'user.message', ?, '{}'::jsonb FROM generate_series(1, ?) n",
                sessionId,
                NOW,
                entryCount);
        jdbcTemplate.update(
                "INSERT INTO t_session_events "
                        + "(session_id,event_id,event_seq,anchor_entry_id,type,created_at,payload) "
                        + "SELECT ?, 'event-' || n, n * 3 - 1, 'entry-' || n, "
                        + "'user.message', ?, '{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}'::jsonb "
                        + "FROM generate_series(1, ?) n",
                sessionId,
                NOW,
                entryCount);
        jdbcTemplate.update(
                "INSERT INTO t_session_event_projection (session_id,anchor_entry_id,event_count) "
                        + "SELECT ?, 'entry-' || n, 1 FROM generate_series(1, ?) n",
                sessionId,
                entryCount);
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-" + entryCount, sessionId);
    }

    private void insertEntry(String sessionId, String entryId, long sequence, String parentId) {
        insertEntry(sessionId, entryId, sequence, parentId, "user.message");
    }

    private void insertEntry(String sessionId, String entryId, long sequence, String parentId, String type) {
        jdbcTemplate.update(
                "INSERT INTO t_session_entries "
                        + "(session_id,id,entry_seq,parent_id,type,timestamp,payload) "
                        + "VALUES (?,?,?,?,?,?,CAST(? AS JSONB))",
                sessionId,
                entryId,
                sequence,
                parentId,
                type,
                NOW,
                "{}");
    }

    private void insertEvent(String sessionId, String eventId, long sequence, String anchorId) {
        jdbcTemplate.update(
                "INSERT INTO t_session_events "
                        + "(session_id,event_id,event_seq,anchor_entry_id,type,created_at,payload) "
                        + "VALUES (?,?,?,?,?,?,CAST(? AS JSONB))",
                sessionId,
                eventId,
                sequence,
                anchorId,
                "user.message",
                NOW,
                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
    }

    private void insertProjection(String sessionId, String entryId, int eventCount) {
        jdbcTemplate.update(
                "INSERT INTO t_session_event_projection " + "(session_id,anchor_entry_id,event_count) VALUES (?,?,?)",
                sessionId,
                entryId,
                eventCount);
    }

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session_event_page");
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setModelId("model-db-it");
        session.setState("idle");
        session.setMode("build");
        session.setResourceVersion(1L);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        session.setCwd("/tmp/campusclaw-db-it");
        return session;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }
}
