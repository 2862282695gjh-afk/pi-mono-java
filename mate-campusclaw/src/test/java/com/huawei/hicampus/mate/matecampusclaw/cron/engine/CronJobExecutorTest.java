/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.cron.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.huawei.hicampus.mate.matecampusclaw.agent.error.StableErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronJob;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronPayload;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronRunRecord;
import com.huawei.hicampus.mate.matecampusclaw.cron.model.CronSchedule;
import com.huawei.hicampus.mate.matecampusclaw.cron.store.CronRunLog;

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
        assertEquals("MATE_RESPONSE_INVALID", record.errorCode());
        assertNull(record.errorMessage());
    }

    @Test
    void uncodedFailureUsesGenericStableCodeWithoutDiagnosticText() {
        CronRunLog runLog = mock(CronRunLog.class);
        CronJobExecutor executor = new CronJobExecutor(runLog, (agentId, prompt) -> {
            throw new IllegalStateException("plain failure");
        });

        CronRunRecord record = executor.execute(job());

        assertEquals(CronRunRecord.RunStatus.FAILED, record.status());
        assertEquals("CRON_EXECUTION_FAILED", record.errorCode());
        assertNull(record.errorMessage());
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
