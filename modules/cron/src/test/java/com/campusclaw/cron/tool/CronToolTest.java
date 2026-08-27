/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.campusclaw.ai.types.TextContent;
import com.campusclaw.cron.CronService;
import com.campusclaw.cron.model.CronJob;
import com.campusclaw.cron.model.CronJobState;
import com.campusclaw.cron.model.CronPayload;
import com.campusclaw.cron.model.CronRunRecord;
import com.campusclaw.cron.model.CronSchedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CronToolTest {

    private static final String AGENT_ID = "agent-test";

    private CronService service;

    private CronTool tool;

    @BeforeEach
    void setUp() {
        service = mock(CronService.class);
        tool = new CronTool(service, AGENT_ID);
    }

    @Test
    void publishesPascalCaseContractWithoutModelPromptOrTools() {
        assertThat(tool.name()).isEqualTo("Cron");
        assertThat(tool.parameters().path("oneOf").size()).isEqualTo(6);
        assertThat(tool.parameters().path("properties").has("model")).isFalse();
        assertThat(tool.parameters().path("properties").has("system_prompt")).isFalse();
        assertThat(tool.parameters().path("properties").has("tools")).isFalse();
    }

    @Test
    void createAutomaticallyBindsCurrentAgent() {
        CronJob created = job("job-1", AGENT_ID);
        when(service.createJob(eq("daily"), eq(null), any(), any())).thenReturn(created);

        String output = execute(Map.of(
                "action", "create",
                "name", "daily",
                "prompt", "prepare report",
                "schedule_type", "every",
                "schedule_value", "60000"));

        assertThat(output).contains("job-1");
        var payload = org.mockito.ArgumentCaptor.forClass(CronPayload.class);
        org.mockito.Mockito.verify(service).createJob(eq("daily"), eq(null), any(), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new CronPayload.AgentPrompt(AGENT_ID, "prepare report"));
    }

    @Test
    void listOnlyReturnsJobsOwnedByCurrentAgent() {
        when(service.listJobs()).thenReturn(List.of(job("mine", AGENT_ID), job("other", "agent-other")));

        assertThat(execute(Map.of("action", "list"))).contains("mine").doesNotContain("other");
    }

    @Test
    void managementCannotAddressAnotherAgentsJob() {
        when(service.getJob("other")).thenReturn(Optional.of(job("other", "agent-other")));

        assertThatThrownBy(() -> execute(Map.of("action", "delete", "job_id", "other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cron Job not found");
        verify(service, org.mockito.Mockito.never()).deleteJob("other");
    }

    @Test
    void triggerAndRunsUseOwnedJob() {
        when(service.getJob("mine")).thenReturn(Optional.of(job("mine", AGENT_ID)));
        when(service.triggerJob("mine"))
<<<<<<< HEAD
                .thenReturn(new CronRunRecord("run-1", "mine", 1, 2, CronRunRecord.RunStatus.SUCCESS, null, "ok", 0));
        when(service.getRecentRuns("mine", 10))
                .thenReturn(List.of(
                        new CronRunRecord("run-1", "mine", 1, 2, CronRunRecord.RunStatus.SUCCESS, null, "ok", 0)));
=======
                .thenReturn(
                        new CronRunRecord("run-1", "mine", 1, 2, CronRunRecord.RunStatus.SUCCESS, null, null, "ok", 0));
        when(service.getRecentRuns("mine", 10))
                .thenReturn(List.of(new CronRunRecord(
                        "run-1", "mine", 1, 2, CronRunRecord.RunStatus.SUCCESS, null, null, "ok", 0)));
>>>>>>> upstream/main

        assertThat(execute(Map.of("action", "trigger", "job_id", "mine"))).contains("run-1");
        assertThat(execute(Map.of("action", "runs", "job_id", "mine"))).contains("SUCCESS");
    }

    @Test
    void atScheduleAcceptsOnlyIsoTimestamp() {
        assertThatThrownBy(() -> execute(Map.of(
                        "action", "create",
                        "name", "once",
                        "prompt", "prepare report",
                        "schedule_type", "at",
                        "schedule_value", "1724432400000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid at schedule");
    }

    @Test
    void cronScheduleRejectsInvalidTimezoneAtCreation() {
        assertThatThrownBy(() -> execute(Map.of(
                        "action", "create",
                        "name", "daily",
                        "prompt", "prepare report",
                        "schedule_type", "cron",
                        "schedule_value", "0 0 * * * *",
                        "timezone", "Invalid/Timezone")))
                .isInstanceOf(java.time.DateTimeException.class);
    }

    private String execute(Map<String, Object> parameters) {
        var result = tool.execute("call", parameters, null, ignored -> {});
        return ((TextContent) result.content().getFirst()).text();
    }

    private static CronJob job(String id, String agentId) {
        return new CronJob(
                id,
                id,
                null,
                true,
                false,
                new CronSchedule.Every(60_000),
                new CronPayload.AgentPrompt(agentId, "prompt"),
                CronJobState.initial(),
                0);
    }
}
