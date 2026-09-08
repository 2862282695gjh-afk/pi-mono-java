/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.event.MessageEndEvent;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.session.compaction.CompactionReason;
import com.campusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.campusclaw.codingagent.session.compaction.SessionCompactionStartedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;

/**
 * 不创建 SSE 流，组合真实工厂、投影器和编解码器验证压缩持久化及恢复边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimePersistenceOnlyProjectorTest {
    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);

    private final RuntimeEntryCodec codec =
            spy(new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource()));

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final List<RuntimeEntryDTO> history = new ArrayList<>();

    private final List<RuntimeRecordDTO> records = new ArrayList<>();

    private final List<CommittedEventDTO> committedEvents = new ArrayList<>();

    private final AtomicInteger sequence = new AtomicInteger(41);

    private final RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());

    private final RuntimeEventProjector projector;

    RuntimePersistenceOnlyProjectorTest() {
        when(holder.sessionId()).thenReturn("session");
        execution.beginRun("internal-compaction-run");
        AtomicInteger ids = new AtomicInteger();
        var factory = new RuntimeEventProjectorFactory(
                repository,
                codec,
                new RuntimeCommittedEventFactory(
                        new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource()),
                () -> "entry-" + ids.incrementAndGet(),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        projector = factory.createForCompaction(holder, execution, Locale.US);
        when(repository.listCurrentBranchEntries("session", 0L, 500)).thenAnswer(call -> List.copyOf(history));
        when(repository.appendEntry(any())).thenAnswer(call -> saveEntry(call.getArgument(0)));
        when(repository.appendEntryWithUsage(any(), any(), any())).thenAnswer(call -> {
            RuntimeEntryDTO persisted = saveEntry(call.getArgument(0));
            RuntimeRecordDTO record = call.getArgument(1);
            record.setRecordSeq(sequence.getAndIncrement());
            records.add(record);
            return persisted;
        });
        when(repository.appendEntryWithUsage(any(), any(), any(), any())).thenAnswer(call -> {
            committedEvents.addAll(call.getArgument(3));
            RuntimeEntryDTO persisted = saveEntry(call.getArgument(0));
            RuntimeRecordDTO record = call.getArgument(1);
            record.setRecordSeq(sequence.getAndIncrement());
            records.add(record);
            return persisted;
        });
    }

    @Test
    void shouldPersistManualCompactionAndRestoreRetainedToolPairWithoutSse() {
        addUser("old", "old task");
        addUser("kept", "current task");
        AssistantMessage call =
                assistant(List.of(new ToolCall("call", "Read", Map.of("path", "a"))), StopReason.TOOL_USE);
        history.add(codec.assistantEntry("session", "assistant", call, now));
        history.add(codec.toolResultEntry(
                "session",
                "tool",
                new ToolResultMessage("call", "Read", List.of(new TextContent("contents")), null, false, 1L),
                now));

        complete(CompactionReason.MANUAL, 1, false);

        RuntimeEntryDTO persisted = history.getLast();
        assertThat(projector.failure()).isNull();
        assertThat(projector.lastCompactionEntrySeq()).isEqualTo(41L);
        assertThat(persisted.getType()).isEqualTo("session.compaction.completed");
        assertThat(persisted.getPayload())
                .contains("\"firstKeptEntryId\":\"kept\"", "\"reason\":\"manual\"")
                .doesNotContain("_discardedEntryId", "commandId");
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getRecordSeq()).isEqualTo(42L);
            assertThat(record.getRunId()).isEqualTo("internal-compaction-run");
            assertThat(record.getPayload()).contains("\"cause\":\"compaction\"", "\"entryId\":\"" + persisted.getId());
        });
        assertThat(committedEvents).singleElement().satisfies(event -> {
            assertThat(event.getEventId()).isEqualTo(persisted.getId());
            assertThat(event.getAnchorEntryId()).isEqualTo(persisted.getId());
            assertThat(event.getType()).isEqualTo("session.compacted");
            assertThat(event.getPayload())
                    .contains("\"reason\":\"manual\"", "\"tokensBefore\":100", "\"estimatedTokensAfter\":20")
                    .doesNotContain("summary", "sourceEventId");
        });
        Model model = mock(Model.class);
        when(model.api()).thenReturn(Api.ANTHROPIC_MESSAGES);
        when(model.provider()).thenReturn(Provider.ANTHROPIC);
        List<Message> restored = codec.toAgentMessages(history, model);
        assertThat(restored).hasSize(4);
        assertThat(((UserMessage) restored.getFirst()).content())
                .containsExactly(
                        new TextContent(
                                "The conversation history before this point was compacted into the following summary:\n\n<summary>\nsummary\n</summary>"));
        assertThat(((UserMessage) restored.get(1)).content()).containsExactly(new TextContent("current task"));
        assertThat(((AssistantMessage) restored.get(2)).content()).containsExactlyElementsOf(call.content());
        assertThat(((ToolResultMessage) restored.getLast()).toolCallId()).isEqualTo("call");
        verify(codec, never()).toSseData(any(), any());
        verify(holder, never()).abort();
    }

    @Test
    void shouldPreserveRetryDiscardIdentityWithoutRequestStream() {
        addUser("old", "old");
        addUser("kept", "task");
        history.add(codec.assistantEntry(
                "session", "discarded", assistant(List.of(new TextContent("partial")), StopReason.LENGTH), now));

        complete(CompactionReason.OVERFLOW, 1, true);
        RuntimeEntryDTO compaction = history.getLast();
        projector.onEvent(new MessageEndEvent(assistant(List.of(new TextContent("done")), StopReason.STOP)));

        assertThat(compaction.getPayload()).contains("\"_discardedEntryId\":\"discarded\"");
        assertThat(codec.toAgentContextEntryIds(history))
                .containsExactly(compaction.getId(), "kept", history.getLast().getId());
        assertThat(records).hasSize(2);
        assertThat(records.getLast().getPayload()).contains("\"cause\":\"assistant\"", "\"attempt\":2");
        assertThat(projector.lastCompactionEntrySeq()).isEqualTo(41L);
        assertThat(projector.failure()).isNull();
    }

    @Test
    void shouldAbortOnceAndExposeNoSequenceAfterPersistenceFailure() {
        addUser("kept", "task");
        IllegalStateException error = new IllegalStateException("database unavailable");
        doThrow(error).when(repository).appendEntryWithUsage(any(), any(), any(), any());

        complete(CompactionReason.MANUAL, 0, false);
        complete(CompactionReason.MANUAL, 0, false);
        projector.onEvent(new MessageEndEvent(new UserMessage("ignored", 2L)));

        assertThat(projector.failure()).isSameAs(error);
        assertThat(projector.lastCompactionEntrySeq()).isNull();
        assertThat(history).hasSize(1);
        verify(holder).abort();
        verify(repository).appendEntryWithUsage(any(), any(), any(), any());
        verify(repository, never()).appendEntry(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void shouldRejectMissingRetainedBoundaryBeforePersistence(int firstKept) {
        addUser("only", "task");

        complete(CompactionReason.MANUAL, firstKept, false);

        assertThat(projector.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("compaction retained boundary is not present in runtime history");
        assertThat(projector.lastCompactionEntrySeq()).isNull();
        verify(holder).abort();
        verify(repository, never()).appendEntryWithUsage(any(), any(), any(), any());
    }

    @Test
    void shouldLeaveTransientLifecycleWithoutEntryOrSequence() {
        projector.onCompactionEvent(new SessionCompactionStartedEvent(CompactionReason.MANUAL, false));
        projector.onCompactionEvent(
                new SessionCompactionFailedEvent(CompactionReason.MANUAL, false, true, "compaction failed"));

        assertThat(projector.lastCompactionEntrySeq()).isNull();
        assertThat(projector.failure()).isNull();
        verify(repository, never()).appendEntry(any());
        verify(repository, never()).appendEntryWithUsage(any(), any(), any(), any());
        verify(repository, never()).listCurrentBranchEntries(any(), any(Long.class), any(Integer.class));
    }

    @Test
    void shouldReadAllBranchBatchesBeforeMappingRetainedBoundary() {
        for (int index = 0; index < 501; index++) {
            addUser("user-" + index, "task-" + index);
            history.getLast().setEntrySeq(index + 1L);
        }
        when(repository.listCurrentBranchEntries("session", 0L, 500)).thenReturn(List.copyOf(history.subList(0, 500)));
        when(repository.listCurrentBranchEntries("session", 500L, 500)).thenReturn(List.of(history.getLast()));
        sequence.set(502);

        complete(CompactionReason.MANUAL, 500, false);

        assertThat(projector.failure()).isNull();
        assertThat(history.getLast().getPayload()).contains("\"firstKeptEntryId\":\"user-500\"");
        verify(repository).listCurrentBranchEntries("session", 500L, 500);
    }

    @Test
    void shouldExposeLatestPersistedCompactionRatherThanUsageSequence() {
        addUser("old", "old");
        addUser("kept", "task");
        complete(CompactionReason.MANUAL, 1, false);
        complete(CompactionReason.MANUAL, 1, false);

        assertThat(projector.lastCompactionEntrySeq()).isEqualTo(43L);
        assertThat(records).extracting(RuntimeRecordDTO::getRecordSeq).containsExactly(42L, 44L);
        verify(repository, times(2)).appendEntryWithUsage(any(), any(), any(), any());
    }

    private void addUser(String id, String text) {
        history.add(codec.userEntry("session", id, text, List.of(), now));
    }

    private RuntimeEntryDTO saveEntry(RuntimeEntryDTO input) {
        RuntimeEntryDTO persisted = new RuntimeEntryDTO();
        BeanUtils.copyProperties(input, persisted);
        persisted.setEntrySeq(sequence.getAndIncrement());
        history.add(persisted);
        return persisted;
    }

    private void complete(CompactionReason reason, int firstKept, boolean retry) {
        SessionCompactionResult result = new SessionCompactionResult(
                "summary", List.of(new UserMessage("task", 1L)), firstKept, 100, 20, Usage.empty());
        projector.onCompactionEvent(new SessionCompactionCompletedEvent(reason, result, retry));
    }

    private static AssistantMessage assistant(List<ContentBlock> content, StopReason reason) {
        return new AssistantMessage(content, "api", "provider", "model", null, Usage.empty(), reason, null, 1L);
    }
}
