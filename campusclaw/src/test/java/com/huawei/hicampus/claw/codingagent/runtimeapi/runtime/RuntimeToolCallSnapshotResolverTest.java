/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.codingagent.tool.builtin.UnavailableBuiltInTool;
import com.huawei.hicampus.claw.codingagent.tool.mate.CallMateTool;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolSessionState;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolsetFactory;
import com.huawei.hicampus.claw.codingagent.tool.mate.MockMateToolClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeToolCallSnapshotResolver} 的服务端名称映射与调用隔离测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeToolCallSnapshotResolverTest {

    private static final String TOOL_ID = "tool-11111111111111111111111111111111";

    @Test
    void ordinaryToolShouldUseNormalizedLabelInsteadOfTechnicalName() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenReturn("  Read file  ");
        RuntimeToolCallSnapshotResolver resolver = RuntimeToolCallSnapshotResolver.from(List.of(tool));

        String displayName = resolver.resolveDisplayName(new ToolCall("call-1", "Read", Map.of("path", "a")));

        assertThat(displayName).isEqualTo("Read file");
    }

    @Test
    void blankOrdinaryLabelShouldRemainAbsent() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenReturn(" \n ");
        RuntimeToolCallSnapshotResolver resolver = RuntimeToolCallSnapshotResolver.from(List.of(tool));

        assertThat(resolver.resolveDisplayName(new ToolCall("call-1", "Read", Map.of())))
                .isNull();
    }

    @Test
    void mateToolShouldUseBusinessMetadataAndIgnoreForgedArgument() {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(
                new MateToolMeta(TOOL_ID, "Query", "Business query", "description", Map.of(), Map.of(), true, "allow"));
        client.bindAgent("agent-1", List.of(TOOL_ID));
        MateToolSessionState state = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        CallMateTool tool = state.createCallTool();
        RuntimeToolCallSnapshotResolver resolver = RuntimeToolCallSnapshotResolver.from(List.of(tool));
        ToolCall call = new ToolCall("call-1", "CallMateTool", Map.of("tool", "Query", "displayName", "Forged name"));

        String displayName = resolver.resolveDisplayName(call);

        assertThat(displayName).isEqualTo("Business query");
        assertThat(displayName).isNotEqualTo(tool.label());
        assertThat(client.agentListCalls()).isEqualTo(1);
    }

    @Test
    void unavailableMateToolShouldNotExposeWrapperLabel() {
        AgentTool tool =
                new UnavailableBuiltInTool(BuiltInToolName.CALL_MATE_TOOL, new ObjectMapper().createObjectNode());
        RuntimeToolCallSnapshotResolver resolver = RuntimeToolCallSnapshotResolver.from(List.of(tool));

        String displayName =
                resolver.resolveDisplayName(new ToolCall("call-1", "CallMateTool", Map.of("tool", "Query")));

        assertThat(displayName).isNull();
    }
}
