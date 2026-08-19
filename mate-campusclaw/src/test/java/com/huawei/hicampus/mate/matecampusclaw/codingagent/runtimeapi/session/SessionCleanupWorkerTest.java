/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime Session 异步清理任务的调度与重试单元测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class SessionCleanupWorkerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T00:00:00Z");

    private RuntimeSessionRepository repository;

    private SessionCleanupWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(RuntimeSessionRepository.class);
        RuntimeCleanupProperties properties = new RuntimeCleanupProperties();
        properties.setBatchSize(2);
        properties.setRetryDelay(Duration.ofSeconds(30));
        properties.setRunningTimeout(Duration.ofMinutes(5));
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
        worker = new SessionCleanupWorker(repository, properties, clock);
    }

    @Test
    void completesClaimedTaskAndStopsWhenQueueIsEmpty() {
        when(repository.claimCleanupTask(NOW, NOW.minusMinutes(5)))
                .thenReturn(Optional.of("session-cleanup"), Optional.empty());

        worker.cleanAvailableTasks();

        verify(repository).completeCleanup("session-cleanup");
    }

    @Test
    void schedulesRetryWhenPhysicalCleanupFails() {
        when(repository.claimCleanupTask(NOW, NOW.minusMinutes(5)))
                .thenReturn(Optional.of("session-retry"), Optional.empty());
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository)
                .completeCleanup("session-retry");

        worker.cleanAvailableTasks();

        verify(repository)
                .retryCleanup("session-retry", NOW, NOW.plusSeconds(30), IllegalStateException.class.getSimpleName());
    }
}
