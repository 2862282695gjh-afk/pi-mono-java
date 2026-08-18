/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.delegation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;

import org.junit.jupiter.api.Test;

class InvokeAgentToolTest {

    private final InvokeAgentTool tool = new InvokeAgentTool();

    @Test
    void acknowledgesValidRequestWithTargetId() throws Exception {
        AgentToolResult result = tool.execute(
                "call-1",
                Map.of("agentId", "agent-2", "task", "Summarize the incident"),
                new CancellationToken(),
                null);

        assertTrue(result.content().getFirst() instanceof TextContent text
                && text.text().contains("agent-2"));
    }

    @Test
    void rejectsMissingAgentId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute("call-1", Map.of("task", "t"), new CancellationToken(), null));
    }

    @Test
    void rejectsBlankTask() {
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute("call-1", Map.of("agentId", "a", "task", " "), new CancellationToken(), null));
    }

    @Test
    void describedWithEnumeratesCandidatesWithActualVersion() {
        var adorned = tool.describedWith(List.of(
                new ChildAgentSummary("agent-2", "field-ops", "Field Ops", "Handles on-site operations", "2.1.0"),
                new ChildAgentSummary("agent-5", null, null, null, null)));

        assertEquals(InvokeAgentTool.NAME, adorned.name());
        String description = adorned.description();
        assertTrue(description.contains("agent-2"));
        assertTrue(description.contains("Handles on-site operations"));
        assertTrue(description.contains("version 2.1.0"));
        assertTrue(description.contains("agent-5"));
        assertTrue(description.contains("no description"));
    }

    @Test
    void adornedViewDelegatesExecutionUnchanged() throws Exception {
        var adorned = tool.describedWith(List.of(new ChildAgentSummary("agent-2", "field-ops", null, "desc", null)));

        AgentToolResult result =
                adorned.execute("call-1", Map.of("agentId", "agent-2", "task", "do it"), new CancellationToken(), null);

        assertTrue(result.content().getFirst() instanceof TextContent text
                && text.text().contains("agent-2"));
    }
}
