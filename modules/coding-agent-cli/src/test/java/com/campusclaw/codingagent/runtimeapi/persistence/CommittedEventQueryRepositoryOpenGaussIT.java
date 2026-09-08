/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 openGauss 验证公共事件当前分支分页查询。
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
        jdbcTemplate.update("TRUNCATE TABLE t_session_events");
        jdbcTemplate.update("TRUNCATE TABLE t_session_entries");
        jdbcTemplate.update("TRUNCATE TABLE t_session_stats");
        jdbcTemplate.update("TRUNCATE TABLE t_session_sequences");
        jdbcTemplate.update("TRUNCATE TABLE t_session_materialized");
        jdbcTemplate.update("TRUNCATE TABLE t_sessions");
    }

    @Test
    void shouldReadOnlyCurrentBranchWithNumericOffset() {
        RuntimeSessionDTO session = session();
        repository.create(session);
        insertEntry(session.getId(), "entry-root", 1L, null);
        insertEntry(session.getId(), "entry-abandoned", 3L, "entry-root");
        insertEntry(session.getId(), "entry-current", 5L, "entry-root");
        insertEvent(session.getId(), "event-root", 2L, "entry-root");
        insertEvent(session.getId(), "event-abandoned", 4L, "entry-abandoned");
        insertEvent(session.getId(), "event-current", 6L, "entry-current");
        jdbcTemplate.update("UPDATE t_sessions SET active_leaf_id = ? WHERE id = ?", "entry-current", session.getId());

        var firstPage = repository.findEventPage(session.getId(), 0L, 1).orElseThrow();
        var secondPage = repository.findEventPage(session.getId(), 1L, 2).orElseThrow();

        assertThat(firstPage).extracting(CommittedEventDTO::getEventId).containsExactly("event-root");
        assertThat(secondPage).extracting(CommittedEventDTO::getEventId).containsExactly("event-current");
        assertThat(repository.findEventPage("session-missing", 0L, 1)).isEmpty();
    }

    private void insertEntry(String sessionId, String entryId, long sequence, String parentId) {
        jdbcTemplate.update(
                "INSERT INTO t_session_entries "
                        + "(session_id,id,entry_seq,parent_id,type,timestamp,payload) "
                        + "VALUES (?,?,?,?,?,?,CAST(? AS JSONB))",
                sessionId,
                entryId,
                sequence,
                parentId,
                "user.message",
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

    private static RuntimeSessionDTO session() {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session_event_page");
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setModelId("model-db-it");
        session.setState("idle");
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
