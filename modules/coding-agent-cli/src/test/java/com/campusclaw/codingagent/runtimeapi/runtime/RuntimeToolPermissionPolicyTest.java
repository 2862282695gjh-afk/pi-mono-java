/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;

import org.junit.jupiter.api.Test;

/**
 * 可信运行时工具权限快照测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeToolPermissionPolicyTest {
    @Test
    void shouldResolveMateWrapperPermissionFromRuntimeSnapshot() {
        PreparedAgentRuntime runtime = runtime(
                List.of(tool("read_ports", "allow"), tool("isolate_port", "ask")),
                List.of(tool("delete_route", "deny")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);

        assertThat(policy.decide(call("read_ports"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
        assertThat(policy.decide(call("isolate_port"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ASK);
        assertThat(policy.decide(call("delete_route"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(call("unknown"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(new ToolCall("call-read", "Read", Map.of())))
                .isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
    }

    @Test
    void shouldDenyConflictingPermissionsAcrossTrustedSources() {
        PreparedAgentRuntime runtime = runtime(List.of(tool("shared", "allow")), List.of(tool("shared", "ask")));

        assertThat(RuntimeToolPermissionPolicy.from(runtime).decide(call("shared")))
                .isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
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

    private static BoundTool tool(String name, String permission) {
        return new BoundTool(null, null, "tool-id", null, name, permission, null, null);
    }

    private static ToolCall call(String tool) {
        return new ToolCall("call-id", "CallMateTool", Map.of("tool", tool, "args", Map.of()));
    }
}
