/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.compaction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动时恢复遗留的 running 状态 Session。进程重启后内存中的执行已不存在，
 * 数据库中残留的 running 状态是孤儿状态；此组件将其统一恢复为 idle，
 * 避免会话永久卡死。多实例部署需额外引入实例归属标记，当前仅单实例安全。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class CompactionStartupRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactionStartupRecovery.class);

    private final RuntimeSessionRepository repository;

    private final Clock clock;

    public CompactionStartupRecovery(RuntimeSessionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedRunningSessions() {
        List<String> runningIds = repository.findRunningSessionIds();
        if (runningIds.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String sessionId : runningIds) {
            repository.finishExecution(sessionId, now);
            LOGGER.info("Recovered orphaned running session: id={}", sessionId);
        }
        LOGGER.info("Startup recovery completed: {} orphaned running session(s) reset to idle", runningIds.size());
    }
}
