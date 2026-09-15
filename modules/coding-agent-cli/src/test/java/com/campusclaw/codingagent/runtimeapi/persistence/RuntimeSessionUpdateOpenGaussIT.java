/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionUpdateChangesDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionUpdateSpecificationDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 openGauss 验证统一 Session 更新的中途事件失败回滚边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionUpdateOpenGaussIT {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 15, 3, 0, 0, 0, ZoneOffset.UTC);

    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository repository;

    private JdbcTemplate jdbc;

    private RuntimeEntryCodec codec;

    private RuntimeCommittedEventFactory events;

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
        jdbc = context.getBean(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        codec = new RuntimeEntryCodec(mapper, messages);
        events = new RuntimeCommittedEventFactory(mapper, messages);
        truncateRuntimeTables();
    }

    @Test
    void eventFailureRollsBackAllFieldsEntriesEventsAndVersion() {
        RuntimeSessionDTO original = session("session-update-rollback");
        repository.create(original);
        SessionUpdateSpecificationDTO specification = specification(original);
        AtomicInteger eventIndex = new AtomicInteger();

        assertThatThrownBy(() -> repository.updateSession(
                        original.getId(),
                        specification,
                        this::configurationEntries,
                        entry -> duplicateEvent(entry, eventIndex.incrementAndGet())))
                .isInstanceOf(RuntimeException.class);

        RuntimeSessionDTO stored = repository.find(original.getId()).orElseThrow();
        assertThat(stored.getDisplayName()).isEqualTo(original.getDisplayName());
        assertThat(stored.getModelId()).isEqualTo(original.getModelId());
        assertThat(stored.isThinking()).isEqualTo(original.isThinking());
        assertThat(stored.getMode()).isEqualTo(original.getMode());
        assertThat(stored.getResourceVersion()).isEqualTo(original.getResourceVersion());
        assertThat(count("t_session_entries", original.getId())).isZero();
        assertThat(count("t_session_events", original.getId())).isZero();
    }

    private List<RuntimeEntryDTO> configurationEntries(SessionUpdateChangesDTO changes) {
        RuntimeSessionDTO before = changes.previous();
        return List.of(
                codec.modelChangedEntry(
                        before.getId(),
                        "rollback-model",
                        before.getModelId(),
                        changes.modelId(),
                        "requested",
                        NOW.plusSeconds(1)),
                codec.thinkingChangedEntry(
                        before.getId(),
                        "rollback-thinking",
                        before.isThinking(),
                        changes.thinking(),
                        "requested",
                        NOW.plusSeconds(1)),
                codec.modeChangedEntry(
                        before.getId(),
                        "rollback-mode",
                        before.getMode(),
                        changes.mode(),
                        "requested",
                        null,
                        null,
                        NOW.plusSeconds(1)));
    }

    private CommittedEventDTO duplicateEvent(RuntimeEntryDTO entry, int index) {
        CommittedEventDTO event = events.sessionConfiguration(entry);
        event.setEventId("duplicate-update-event");
        assertThat(index).isLessThanOrEqualTo(2);
        return event;
    }

    private static SessionUpdateSpecificationDTO specification(RuntimeSessionDTO session) {
        var value = new SessionUpdateSpecificationDTO();
        value.setExpectedVersion(session.getResourceVersion());
        value.setDisplayNameSubmitted(true);
        value.setDisplayName("changed");
        value.setModelIdSubmitted(true);
        value.setModelId("model-b");
        value.setTargetModelSupportsThinking(true);
        value.setThinkingSubmitted(true);
        value.setThinking(true);
        value.setModeSubmitted(true);
        value.setMode("plan");
        value.setUpdatedAt(NOW.plusSeconds(1));
        return value;
    }

    private static RuntimeSessionDTO session(String id) {
        var value = new RuntimeSessionDTO();
        value.setId(id);
        value.setAgentId("agent-update-rollback");
        value.setDisplayName("original");
        value.setModelId("model-a");
        value.setState("idle");
        value.setThinking(false);
        value.setMode("build");
        value.setResourceVersion(1L);
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        value.setCwd("/tmp/session-update-rollback");
        return value;
    }

    private void truncateRuntimeTables() {
        RuntimeOpenGaussTestCleaner.truncate(jdbc);
    }

    private long count(String table, String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }
}
