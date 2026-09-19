/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.MateToolDiscovery;

import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeToolCallSnapshotResolver} 的可信名称映射与降级测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeToolCallSnapshotResolverTest {

    private static final String TOOL_ID = "tool-11111111111111111111111111111111";

    @Test
    void ordinaryToolShouldUseNormalizedLabelFromAssembledInstance() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenReturn("  Read file  ");
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(List.of(), List.of()), List.of(tool));

        String displayName = resolver.resolveDisplayName(new ToolCall("call-1", "Read", Map.of("path", "a")));

        assertThat(displayName).isEqualTo("Read file");
    }

    @Test
    void blankOrdinaryLabelShouldRemainAbsent() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenReturn(" \n ");
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(List.of(), List.of()), List.of(tool));

        assertThat(resolver.resolveDisplayName(new ToolCall("call-1", "Read", Map.of())))
                .isNull();
    }

    @Test
    void mateToolShouldUseBoundMetadataWithoutDiscoveryOrWrapperLabel() {
        MateToolClient client = mock(MateToolClient.class);
        MateToolDiscovery discovery = mock(MateToolDiscovery.class);
        CallMateTool tool = new CallMateTool(client, MateCredentials.empty(), discovery);
        PreparedAgentRuntime runtime = runtime(List.of(tool("Query", TOOL_ID, "  Business query  ")), List.of());
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime, List.of(tool));
        ToolCall call = new ToolCall("call-1", "CallMateTool", Map.of("tool", "Query", "displayName", "Forged"));

        String displayName = resolver.resolveDisplayName(call);

        assertThat(displayName).isEqualTo("Business query");
        assertThat(displayName).isNotEqualTo(tool.label());
        verifyNoInteractions(client, discovery);
    }

    @Test
    void missingBlankOrConflictingMateDisplayMetadataShouldRemainAbsent() {
        List<BoundTool> agentTools = List.of(
                tool("Missing", TOOL_ID, null),
                tool("Blank", TOOL_ID, "  "),
                tool("ConflictingName", TOOL_ID, "Agent name"),
                tool("ConflictingId", TOOL_ID, "Shared name"));
        List<BoundTool> skillTools = List.of(
                tool("ConflictingName", TOOL_ID, "Skill name"),
                tool("ConflictingId", "tool-22222222222222222222222222222222", "Shared name"));
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(agentTools, skillTools), List.of());

        assertThat(resolver.resolveDisplayName(mateCall("Missing"))).isNull();
        assertThat(resolver.resolveDisplayName(mateCall("Blank"))).isNull();
        assertThat(resolver.resolveDisplayName(mateCall("ConflictingName"))).isNull();
        assertThat(resolver.resolveDisplayName(mateCall("ConflictingId"))).isNull();
    }

    @Test
    void identicalMateMetadataAcrossSourcesShouldRemainAvailable() {
        BoundTool binding = tool("Query", TOOL_ID, "Business query");
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(List.of(binding), List.of(binding)), List.of());

        assertThat(resolver.resolveDisplayName(mateCall("Query"))).isEqualTo("Business query");
    }

    @Test
    void skillOnlyMateBindingShouldRemainAvailable() {
        BoundTool binding = tool("Query", TOOL_ID, "Skill query");
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(List.of(), List.of(binding)), List.of());

        assertThat(resolver.resolveDisplayName(mateCall("Query"))).isEqualTo("Skill query");
    }

    @Test
    void failedOrdinaryLabelShouldDegradeWithoutBlockingToolCall() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenThrow(new IllegalStateException("label unavailable"));
        RuntimeToolCallSnapshotResolver resolver = resolver(runtime(List.of(), List.of()), List.of(tool));

        assertThat(resolver.resolveDisplayName(new ToolCall("call-1", "Read", Map.of())))
                .isNull();
    }

    private static RuntimeToolCallSnapshotResolver resolver(PreparedAgentRuntime runtime, List<AgentTool> tools) {
        return RuntimeToolCallSnapshotResolver.from(runtime, tools);
    }

    private static PreparedAgentRuntime runtime(List<BoundTool> agentTools, List<BoundTool> skillTools) {
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        SkillInfo skill = mock(SkillInfo.class);
        when(metadata.bindingTools()).thenReturn(agentTools);
        when(skill.bindingTools()).thenReturn(skillTools);
        when(runtime.metadata()).thenReturn(metadata);
        when(runtime.skills()).thenReturn(List.of(skill));
        return runtime;
    }

    private static BoundTool tool(String name, String id, String displayName) {
        return new BoundTool(null, displayName, id, null, name, "allow", null, null);
    }

    private static ToolCall mateCall(String toolName) {
        return new ToolCall("call-" + toolName, "CallMateTool", Map.of("tool", toolName));
    }
}
