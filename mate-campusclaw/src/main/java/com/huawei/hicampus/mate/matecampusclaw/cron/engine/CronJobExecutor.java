/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.cron.engine;

import java.util.UUID;

import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJob;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronPayload;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronRunRecord;
import com.huawei.hicampus.mate.matecampusclaw.cron.store.CronRunLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通过宿主提供的公共 Agent Session 边界执行一个 Cron Job。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CronJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronJobExecutor.class);

    private final CronRunLog runLog;

    private final CronAgentSessionRunner sessionRunner;

    public CronJobExecutor(CronRunLog runLog, CronAgentSessionRunner sessionRunner) {
        this.runLog = runLog;
        this.sessionRunner = sessionRunner;
    }

    public CronRunRecord execute(CronJob job) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long startedAt = System.currentTimeMillis();
        append(running(runId, job.id(), startedAt));
        try {
            CronPayload.AgentPrompt payload = (CronPayload.AgentPrompt) job.payload();
            String output = sessionRunner.execute(payload.agentId(), payload.prompt());
            return append(success(runId, job.id(), startedAt, output));
        } catch (RuntimeException exception) {
            log.error("Cron Job {} execution failed: {}", job.id(), exception.getMessage(), exception);
            return append(failed(runId, job.id(), startedAt, publicErrorText(exception)));
        }
    }

    // 运行记录是公开产物:携带稳定错误码的异常只记录错误码,不透传内部诊断文本。
    private static String publicErrorText(RuntimeException exception) {
        if (exception instanceof com.huawei.hicampus.mate.matecampusclaw.agent.error.StableErrorCode coded) {
            return "error-code=" + coded.stableErrorCode();
        }
        return exception.getMessage() != null
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }

    private CronRunRecord append(CronRunRecord record) {
        runLog.appendRun(record);
        return record;
    }

    private static CronRunRecord running(String runId, String jobId, long startedAt) {
        return new CronRunRecord(runId, jobId, startedAt, 0, CronRunRecord.RunStatus.RUNNING, null, null, 0);
    }

    private static CronRunRecord success(String runId, String jobId, long startedAt, String output) {
        return new CronRunRecord(
                runId, jobId, startedAt, System.currentTimeMillis(), CronRunRecord.RunStatus.SUCCESS, null, output, 0);
    }

    private static CronRunRecord failed(String runId, String jobId, long startedAt, String message) {
        return new CronRunRecord(
                runId, jobId, startedAt, System.currentTimeMillis(), CronRunRecord.RunStatus.FAILED, message, null, 0);
    }
}
