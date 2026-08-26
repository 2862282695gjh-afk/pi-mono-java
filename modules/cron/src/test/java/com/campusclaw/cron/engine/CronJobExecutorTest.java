/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.campusclaw.agent.error.StableErrorCode;
import com.campusclaw.cron.model.CronJob;
import com.campusclaw.cron.model.CronPayload;
import com.campusclaw.cron.model.CronRunRecord;
import com.campusclaw.cron.model.CronSchedule;
import com.campusclaw.cron.store.CronRunLog;

import org.junit.jupiter.api.Test;

class CronJobExecutorTest {

    private static final class CodedFailureException extends RuntimeException implements StableErrorCode {

        private CodedFailureException() {
            super("internal english diagnostic that must not leak");
        }

        @Override
        public String stableErrorCode() {
            return "MATE_RESPONSE_INVALID";
        }
    }

    @Test
    void failureRecordCarriesStableErrorCodeInsteadOfDiagnosticText() {
        CronRunLog runLog = mock(CronRunLog.class);
        CronJobExecutor executor = new CronJobExecutor(runLog, (agentId, prompt) -> {
            throw new CodedFailureException();
        });

        CronRunRecord record = executor.execute(job());

        assertEquals(CronRunRecord.RunStatus.FAILED, record.status());
        assertEquals("error-code=MATE_RESPONSE_INVALID", record.error());
    }

    @Test
    void uncodedFailureStillRecordsExceptionMessage() {
        CronRunLog runLog = mock(CronRunLog.class);
        CronJobExecutor executor = new CronJobExecutor(runLog, (agentId, prompt) -> {
            throw new IllegalStateException("plain failure");
        });

        CronRunRecord record = executor.execute(job());

        assertEquals(CronRunRecord.RunStatus.FAILED, record.status());
        assertEquals("plain failure", record.error());
        assertTrue(record.runId() != null && !record.runId().isBlank());
    }

    private static CronJob job() {
        return CronJob.create(
                "nightly-report",
                null,
                new CronSchedule.Every(60_000L),
                new CronPayload.AgentPrompt("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "hi"));
    }
}
