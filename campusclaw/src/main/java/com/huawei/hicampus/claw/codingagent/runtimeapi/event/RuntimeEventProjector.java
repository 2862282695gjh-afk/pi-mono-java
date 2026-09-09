/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionStartedEvent;

/**
 * 把公共 Session 压缩事件投影为权威 Entry 和公共事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeEventProjector {
    private static final int ENTRY_BATCH_SIZE = 500;

    private final String sessionId;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeCommittedEventFactory committedEvents;

    private final RuntimeEntryIdGenerator idGenerator;

    private final RuntimeEventOutput output;

    private final Clock clock;

    private final Runnable abort;

    private final RuntimeActiveExecution execution;

    private final Locale locale;

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private int assistantAttempt = 1;

    private Long lastCompactionEntrySeq;

    public RuntimeEventProjector(
            String sessionId,
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeCommittedEventFactory committedEvents,
            RuntimeEntryIdGenerator idGenerator,
            RuntimeEventOutput output,
            Clock clock,
            Runnable abort,
            RuntimeActiveExecution execution,
            Locale locale) {
        this.sessionId = sessionId;
        this.repository = repository;
        this.codec = codec;
        this.committedEvents = committedEvents;
        this.idGenerator = idGenerator;
        this.output = output;
        this.clock = clock;
        this.abort = abort;
        this.execution = execution;
        this.locale = locale;
    }

    public Throwable failure() {
        return failure.get();
    }

    /**
     * 返回最近一次成功追加的压缩 Entry 序号，不使用后续 Usage 序号。
     *
     * <p>后续压缩失败不会清除旧值；调用方须检查本次失败并固定结果。
     *
     * @return 当前投影器尚未成功追加压缩 Entry 时为 null，否则为最近成功的序号
     */
    public synchronized Long lastCompactionEntrySeq() {
        return lastCompactionEntrySeq;
    }

    public synchronized void onCompactionEvent(SessionCompactionEvent event) {
        if (failure.get() != null) {
            return;
        }
        try {
            switch (event) {
                case SessionCompactionStartedEvent started -> projectCompactionStarted(started);
                case SessionCompactionCompletedEvent completed -> projectCompactionCompleted(completed);
                case SessionCompactionFailedEvent failed -> projectCompactionFailed(failed);
                default ->
                    throw new IllegalArgumentException("unsupported session compaction event: "
                            + event.getClass().getName());
            }
        } catch (RuntimeException error) {
            if (failure.compareAndSet(null, error)) {
                abort.run();
            }
        }
    }

    private void projectCompactionStarted(SessionCompactionStartedEvent event) {
        LinkedHashMap<String, Object> data =
                compactionLifecycleData(event.reason().value(), event.willRetry());
        output.emit(() -> new RuntimeSseEventVO(null, RuntimeEventType.SESSION_COMPACTION_STARTED.value(), data));
    }

    private void projectCompactionFailed(SessionCompactionFailedEvent event) {
        LinkedHashMap<String, Object> data =
                compactionLifecycleData(event.reason().value(), event.willRetry());
        data.put("aborted", event.aborted());
        data.put("message", event.message());
        output.emit(() -> new RuntimeSseEventVO(null, RuntimeEventType.SESSION_COMPACTION_FAILED.value(), data));
    }

    private void projectCompactionCompleted(SessionCompactionCompletedEvent event) {
        List<RuntimeEntryDTO> entries = loadCurrentBranch();
        List<String> contextIds = codec.toAgentContextEntryIds(entries);
        int firstKeptIndex = event.result().compactedMessageCount();
        if (firstKeptIndex < 0 || firstKeptIndex >= contextIds.size()) {
            throw new IllegalStateException("compaction retained boundary is not present in runtime history");
        }
        String discardedEntryId = discardedEntryId(entries, event.willRetry());
        RuntimeEntryDTO entry = codec.compactionEntry(
                sessionId,
                idGenerator.nextId(),
                event.reason(),
                contextIds.get(firstKeptIndex),
                discardedEntryId,
                event.result(),
                event.willRetry(),
                now());
        persistCompaction(event, entry);
        if (event.willRetry()) {
            assistantAttempt++;
        }
    }

    private void persistCompaction(SessionCompactionCompletedEvent event, RuntimeEntryDTO entry) {
        RuntimeRecordDTO record = codec.usageRecord(
                sessionId,
                idGenerator.nextId(),
                execution.runId(),
                RuntimeUsageCause.COMPACTION,
                entry.getId(),
                assistantAttempt,
                null,
                event.result().usage(),
                entry.getTimestamp());
        CommittedEventDTO committed = committedEvents.sessionCompacted(
                entry,
                entry.getId(),
                event.reason().value(),
                event.result().tokensBefore(),
                event.result().estimatedTokensAfter(),
                null);
        RuntimeEntryDTO persisted =
                repository.appendEntryWithUsage(entry, record, event.result().usage(), List.of(committed));
        lastCompactionEntrySeq = persisted.getEntrySeq();
        emitPersisted(persisted);
    }

    private String discardedEntryId(List<RuntimeEntryDTO> entries, boolean willRetry) {
        if (!willRetry) {
            return null;
        }
        String entryId = codec.lastRetriableAssistantEntryId(entries);
        if (entryId == null) {
            throw new IllegalStateException("compaction retry candidate is not present in runtime history");
        }
        return entryId;
    }

    private List<RuntimeEntryDTO> loadCurrentBranch() {
        List<RuntimeEntryDTO> entries = new java.util.ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<RuntimeEntryDTO> batch = repository.listCurrentBranchEntries(sessionId, afterSeq, ENTRY_BATCH_SIZE);
            entries.addAll(batch);
            if (batch.size() < ENTRY_BATCH_SIZE) {
                return List.copyOf(entries);
            }
            afterSeq = batch.getLast().getEntrySeq();
        }
    }

    private static LinkedHashMap<String, Object> compactionLifecycleData(String reason, boolean willRetry) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("willRetry", willRetry);
        return data;
    }

    private void emitPersisted(RuntimeEntryDTO entry) {
        output.emit(() -> new RuntimeSseEventVO(
                Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry, locale)));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
