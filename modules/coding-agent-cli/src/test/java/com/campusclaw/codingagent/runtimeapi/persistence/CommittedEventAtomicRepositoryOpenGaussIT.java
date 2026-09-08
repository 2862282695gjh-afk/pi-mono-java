/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.event.CommittedEventProjection;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 openGauss 验证权威公共事件的时间精度与原子写入。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class CommittedEventAtomicRepositoryOpenGaussIT {
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
        jdbcTemplate.update("TRUNCATE TABLE t_session_events");
        jdbcTemplate.update("TRUNCATE TABLE t_session_records");
        jdbcTemplate.update("TRUNCATE TABLE t_session_entries");
        jdbcTemplate.update("TRUNCATE TABLE t_session_stats");
        jdbcTemplate.update("TRUNCATE TABLE t_session_sequences");
        jdbcTemplate.update("TRUNCATE TABLE t_session_materialized");
        jdbcTemplate.update("TRUNCATE TABLE t_sessions");
    }

    @Test
    void shouldPersistAndProjectSameUtcMillisecondTimestamp() {
        RuntimeSessionDTO session = session("session_event_time");
        repository.create(session);
        RuntimeEntryDTO entry = entry(session.getId(), "entry-user", "user.message");
        CommittedEventDTO event = event(session.getId(), "event-user", "entry-user", "user.message");
        event.setCreatedAt(OffsetDateTime.parse("2026-09-08T09:02:03.456789+08:00"));

        repository.acceptUserEvent(session.getId(), entry, event, NOW);

        OffsetDateTime stored = value(
                "SELECT created_at FROM t_session_events WHERE session_id = ?", OffsetDateTime.class, session.getId());
        String projected =
                new CommittedEventProjection(new ObjectMapper()).project(event).getCreatedAt();
        assertThat(event.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-09-08T01:02:03.456Z"));
        assertThat(stored).isEqualTo(event.getCreatedAt());
        assertThat(projected).isEqualTo("2026-09-08T01:02:03.456Z");
    }

    @Test
    void shouldRollbackEntryRecordUsageAndSequenceWhenPublicEventFails() {
        RuntimeSessionDTO session = session("session_event_rollback");
        repository.create(session);
        RuntimeEntryDTO user = entry(session.getId(), "entry-user", "user.message");
        CommittedEventDTO userEvent = event(session.getId(), "event-user", "entry-user", "user.message");
        repository.acceptUserEvent(session.getId(), user, userEvent, NOW);
        RuntimeEntryDTO assistant = entry(session.getId(), "entry-assistant", "assistant.message.completed");
        RuntimeRecordDTO record = record(session.getId(), "entry-user");
        Usage usage = new Usage(11, 7, 3, 2, 23, new Cost(0.1, 0.2, 0.03, 0.04, 0.37));
        CommittedEventDTO duplicate = event(session.getId(), "event-user", "entry-assistant", "agent.message");

        assertThatThrownBy(() -> repository.appendEntryWithUsage(assistant, record, usage, List.of(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertRollbackState(session.getId());
        assertThat(repository.find(session.getId()).orElseThrow().getActiveLeafId())
                .isEqualTo("entry-user");
    }

    private void assertRollbackState(String sessionId) {
        assertThat(count("t_session_entries", sessionId)).isOne();
        assertThat(count("t_session_records", sessionId)).isZero();
        assertThat(count("t_session_events", sessionId)).isOne();
        assertThat(value("SELECT next_seq FROM t_session_sequences WHERE session_id = ?", Long.class, sessionId))
                .isEqualTo(3L);
        assertThat(value("SELECT input_tokens FROM t_session_stats WHERE session_id = ?", Long.class, sessionId))
                .isZero();
        assertThat(value("SELECT message_count FROM t_session_stats WHERE session_id = ?", Long.class, sessionId))
                .isEqualTo(1L);
        assertThat(value("SELECT cost_total FROM t_session_stats WHERE session_id = ?", BigDecimal.class, sessionId))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private int count(String table, String sessionId) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE session_id = ?", Integer.class, sessionId);
        return result == null ? 0 : result;
    }

    private <T> T value(String sql, Class<T> type, String sessionId) {
        return jdbcTemplate.queryForObject(sql, type, sessionId);
    }

    private static RuntimeSessionDTO session(String sessionId) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(sessionId);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setModelId("model-db-it");
        session.setState("idle");
        session.setResourceVersion(1L);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        session.setCwd("/tmp/campusclaw-db-it");
        return session;
    }

    private static RuntimeEntryDTO entry(String sessionId, String entryId, String type) {
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId(sessionId);
        entry.setId(entryId);
        entry.setType(type);
        entry.setTimestamp(NOW);
        entry.setPayload("{}");
        return entry;
    }

    private static CommittedEventDTO event(String sessionId, String eventId, String anchorId, String type) {
        CommittedEventDTO event = new CommittedEventDTO();
        event.setSessionId(sessionId);
        event.setEventId(eventId);
        event.setAnchorEntryId(anchorId);
        event.setType(type);
        event.setCreatedAt(NOW);
        event.setPayload("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
        return event;
    }

    private static RuntimeRecordDTO record(String sessionId, String runId) {
        RuntimeRecordDTO record = new RuntimeRecordDTO();
        record.setSessionId(sessionId);
        record.setId("record-usage");
        record.setLane("main");
        record.setRunId(runId);
        record.setType("usage");
        record.setTimestamp(NOW);
        record.setPayload("{}");
        return record;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }
}
