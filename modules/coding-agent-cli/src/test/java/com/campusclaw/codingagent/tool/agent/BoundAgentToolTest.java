/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;

import org.junit.jupiter.api.Test;

/**
 * {@link BoundAgentTool} 的直接绑定 Schema 和不可用语义测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class BoundAgentToolTest {

    private static final String PARENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void emptyBindingOmitsEnumAndRejectsEveryExecutionAsUnavailable() {
        BoundAgentTool tool = tool(List.of());

        assertThat(tool.parameters().path("properties").path("agentName").has("enum"))
                .isFalse();
        assertThatThrownBy(() -> tool.execute(
                        "call", Map.of("agentName", "researcher", "task", "research"), null, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent is unavailable in the current execution context");
    }

    @Test
    void directBindingEnumIsStableAndCaseSensitive() {
        AgentReference reviewer = child("reviewer", "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        AgentReference researcher = child("Researcher", "agent-cccccccccccccccccccccccccccccccc");

        var names = tool(List.of(reviewer, researcher))
                .parameters()
                .path("properties")
                .path("agentName")
                .path("enum");

        assertThat(List.of(names.get(0).asText(), names.get(1).asText())).containsExactly("Researcher", "reviewer");
    }

    private static BoundAgentTool tool(List<AgentReference> children) {
        Model model = model();
        PreparedAgentRuntime runtime = prepared(children);
        return new BoundAgentTool(
                runtime,
                SubagentExecutionContext.root(PARENT_ID, model, ThinkingLevel.OFF, MateCredentials.empty()),
                mock(SubagentExecutionService.class));
    }

    private static PreparedAgentRuntime prepared(List<AgentReference> children) {
        AgentRuntime runtime = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                children,
                List.of(),
                "Parent",
                true,
                PARENT_ID,
                "parent",
                "prompt",
                List.of(),
                "1.0.0");
        return new PreparedAgentRuntime(PARENT_ID, Path.of("agent", PARENT_ID), runtime, List.of());
    }

    private static AgentReference child(String name, String id) {
        return new AgentReference(id, name, name, "Child", "1.0.0");
    }

    private static Model model() {
        return new Model(
                "model-a",
                "Model A",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }
}
