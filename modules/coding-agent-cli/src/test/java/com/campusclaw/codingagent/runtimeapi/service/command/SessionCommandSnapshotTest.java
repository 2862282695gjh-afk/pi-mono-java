/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionConfigurationUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.session.SessionEtagFactory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 验证命令保留整个权威资源，并从同源数据组装正文与 ETag，禁止提交后重读。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class SessionCommandSnapshotTest {
    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final AgentDirectoryResolver directories = mock(AgentDirectoryResolver.class);

    private final RuntimeModelManager models = mock(RuntimeModelManager.class);

    private final RuntimeEntryCodec codec = mock(RuntimeEntryCodec.class);

    private final Clock clock = Clock.systemUTC();

    @ParameterizedTest
    @CsvSource({"status,idle", "status,running", "name,idle", "name,running", "thinking,idle", "thinking,running"})
    void shouldPreserveAllQueryFieldsFromOneRead(String command, String state) {
        var authoritative = session("current", 8L);
        authoritative.setState(state);
        authoritative.setDisplayName(null);
        when(repository.find("session"))
                .thenReturn(Optional.of(authoritative))
                .thenThrow(new AssertionError("must not reconstruct the result with another read"));
        var result =
                switch (command) {
                    case "status" -> new RuntimeSessionStatusService(repository).query("session", "");
                    case "name" -> new SessionNamingService(repository, clock).execute("session", "");
                    case "thinking" -> thinkingService().execute("session", "");
                    default -> throw new AssertionError(command);
                };
        assertThat(result.session()).isSameAs(authoritative);
        assertThat(result.changed()).isFalse();
        assertThat(result.sourceEventSeq()).isNull();
        assertProjection(result, 8L, state, null);
        verify(repository).find("session");
        verifyNoInteractions(directories, models, codec);
    }

    @ParameterizedTest
    @CsvSource({"name,true", "name,false", "model,true", "model,false", "thinking,true", "thinking,false"})
    void shouldPreserveLockedResourceForChangedAndUnchangedResults(String command, boolean changed) {
        var beforeLock = session("before", 1L);
        var authoritative = session("locked", changed ? 9L : 8L);
        authoritative.setState(command.equals("name") ? "running" : "idle");
        when(repository.find("session"))
                .thenReturn(Optional.of(beforeLock))
                .thenThrow(new AssertionError("post-commit GET would mix concurrent state"));
        var status =
                changed ? SessionConfigurationUpdateDTO.Status.UPDATED : SessionConfigurationUpdateDTO.Status.UNCHANGED;
        Long sequence = changed && !command.equals("name") ? 17L : null;
        var update = new SessionConfigurationUpdateDTO(status, authoritative, sequence);
        when(repository.updateName(eq("session"), eq("locked"), any()))
                .thenReturn(Optional.of(new SessionNameUpdateDTO(authoritative, changed)));
        when(repository.updateModel(eq("session"), isNull(), eq("locked"), eq(false), any(), any()))
                .thenReturn(update);
        when(repository.updateThinking(eq("session"), isNull(), eq(false), any(), any(), any()))
                .thenReturn(update);
        configureModel();
        var result =
                switch (command) {
                    case "name" -> new SessionNamingService(repository, clock).execute("session", "locked");
                    case "model" ->
                        (SessionCommandResultDTO) new SessionModelConfigurationService(
                                        repository, directories, models, codec, () -> "unused", clock)
                                .execute("session", "locked");
                    case "thinking" -> thinkingService().execute("session", "off");
                    default -> throw new AssertionError(command);
                };
        assertThat(result.session()).isSameAs(authoritative);
        assertThat(result.changed()).isEqualTo(changed);
        assertThat(result.sourceEventSeq()).isEqualTo(sequence);
        assertThat(result.session().getCwd()).isEqualTo("/locked");
        assertThat(result.session().getParentSessionId()).isEqualTo("parent-locked");
        assertThat(result.session().getMetadata()).isEqualTo("{\"from\":\"locked\"}");
        assertThat(result.session().getActiveLeafId()).isEqualTo("leaf-locked");
        assertProjection(result, changed ? 9L : 8L, command.equals("name") ? "running" : "idle", "locked");
        verify(repository, times(command.equals("name") ? 0 : 1)).find("session");
        verify(models, never()).listAvailableModels(any());
    }

    private void assertProjection(SessionCommandResultDTO result, long version, String state, String displayName) {
        var etags = new SessionEtagFactory();
        var view = new RuntimeSessionResponseAssembler(etags).getView(result.session());
        assertThat(view.etag()).isEqualTo(etags.create("session", version));
        assertThat(view.resource().getSessionId()).isEqualTo("session");
        assertThat(view.resource().getAgentId()).isEqualTo("agent");
        assertThat(view.resource().getDisplayName()).isEqualTo(displayName);
        assertThat(view.resource().getState()).isEqualTo(state);
        assertThat(view.resource().getModelId()).isEqualTo(version == 8L && displayName == null ? "current" : "locked");
        assertThat(view.resource().isThinking()).isFalse();
        assertThat(view.resource().getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        assertThat(view.resource().getUpdatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-07T00:00:00Z").plusSeconds(version));
        assertThat(view.resource().getLifetimeUsage())
                .extracting("input", "output", "cacheRead", "cacheWrite", "totalTokens")
                .containsExactly(version, version + 1, version + 2, version + 3, version + 4);
        assertThat(view.resource().getLifetimeUsage().getCost())
                .extracting("input", "output", "cacheRead", "cacheWrite", "total")
                .containsExactly(
                        BigDecimal.valueOf(version),
                        BigDecimal.valueOf(version + 1),
                        BigDecimal.valueOf(version + 2),
                        BigDecimal.valueOf(version + 3),
                        BigDecimal.valueOf(version + 4));
    }

    private void configureModel() {
        var snapshot = new AgentDirectorySnapshotDTO(
                "agent", "before", List.of("locked"), Path.of("/agent"), Path.of("/agent/.campusclaw"));
        when(directories.resolve("agent")).thenReturn(snapshot);
        var model = mock(Model.class);
        when(model.id()).thenReturn("locked");
        when(models.resolveAvailableModel(snapshot, "locked")).thenReturn(model);
    }

    private SessionThinkingConfigurationService thinkingService() {
        return new SessionThinkingConfigurationService(repository, directories, models, codec, () -> "unused", clock);
    }

    private RuntimeSessionDTO session(String label, long version) {
        var value = new RuntimeSessionDTO();
        value.setId("session");
        value.setAgentId("agent");
        value.setDisplayName(label);
        value.setModelId(label);
        value.setState("idle");
        value.setResourceVersion(version);
        value.setCreatedAt(OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        value.setUpdatedAt(OffsetDateTime.parse("2026-09-07T00:00:00Z").plusSeconds(version));
        value.setCwd("/" + label);
        value.setParentSessionId("parent-" + label);
        value.setMetadata("{\"from\":\"" + label + "\"}");
        value.setActiveLeafId("leaf-" + label);
        var usage = value.getLifetimeUsage();
        usage.setInput(version);
        usage.setOutput(version + 1);
        usage.setCacheRead(version + 2);
        usage.setCacheWrite(version + 3);
        usage.setTotalTokens(version + 4);
        usage.setCostInput(BigDecimal.valueOf(version));
        usage.setCostOutput(BigDecimal.valueOf(version + 1));
        usage.setCostCacheRead(BigDecimal.valueOf(version + 2));
        usage.setCostCacheWrite(BigDecimal.valueOf(version + 3));
        usage.setCostTotal(BigDecimal.valueOf(version + 4));
        return value;
    }
}
