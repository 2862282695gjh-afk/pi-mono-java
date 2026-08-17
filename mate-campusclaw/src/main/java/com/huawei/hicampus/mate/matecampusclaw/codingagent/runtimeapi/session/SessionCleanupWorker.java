/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步清理已删除 Session 的历史、序号和物化数据，同时永久保留 tombstone。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
@ConditionalOnProperty(prefix = "campusclaw.runtime.cleanup", name = "enabled", matchIfMissing = true)
public class SessionCleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupWorker.class);

    private final RuntimeSessionRepository repository;

    private final RuntimeCleanupProperties properties;

    private final Clock clock;

    public SessionCleanupWorker(RuntimeSessionRepository repository, RuntimeCleanupProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${campusclaw.runtime.cleanup.poll-interval-ms:5000}")
    public void cleanAvailableTasks() {
        for (int processed = 0; processed < properties.getBatchSize(); processed++) {
            if (!cleanOneTask()) {
                return;
            }
        }
    }

    private boolean cleanOneTask() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime staleBefore = now.minus(properties.getRunningTimeout());
        var sessionId = repository.claimCleanupTask(now, staleBefore);
        if (sessionId.isEmpty()) {
            return false;
        }
        try {
            repository.completeCleanup(sessionId.get());
        } catch (RuntimeException error) {
            OffsetDateTime retryAt = now.plus(properties.getRetryDelay());
            repository.retryCleanup(
                    sessionId.get(), now, retryAt, error.getClass().getSimpleName());
            log.warn("Runtime Session cleanup will be retried: {}", sessionId.get(), error);
        }
        return true;
    }
}
