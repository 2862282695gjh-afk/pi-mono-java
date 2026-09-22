/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.PreparedToolInvocation;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingStatus;

import org.junit.jupiter.api.Test;

/**
 * 可信运行时工具权限快照测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeToolPermissionPolicyTest {
    private static final String TOOL_ID = "tool-11111111111111111111111111111111";

    @Test
    void shouldResolveAgentAndSkillPermissionFromTheirOwnTrustedScopes() {
        PreparedAgentRuntime runtime = runtime(
                List.of(tool("read_ports", "read-id", "allow"), tool("isolate_port", "isolate-id", "ask")),
                List.of(tool("delete_route", "delete-id", "deny")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);

        assertThat(policy.decide(agentCall("read_ports"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
        assertThat(policy.decide(agentCall("isolate_port"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ASK);
        assertThat(policy.decide(callTool("delete_route"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(callTool("read_ports"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(agentCall("delete_route"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(agentCall("unknown"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(new ToolCall("call-read", "Read", Map.of())))
                .isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
    }

    @Test
    void shouldKeepOverlappingAgentAndSkillPermissionsIndependent() {
        PreparedAgentRuntime runtime =
                runtime(List.of(tool("shared", TOOL_ID, "allow")), List.of(tool("shared", TOOL_ID, "ask")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);

        assertThat(policy.decide(agentCall("shared"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
        assertThat(policy.decide(callTool("shared"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.ASK);
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(runtime, "shared")
                        .status())
                .isEqualTo(BindingStatus.BOUND);
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(runtime, "shared"))
                .isEmpty();
    }

    @Test
    void shouldDenyConflictsAndMissingIdentityWithinOneScope() {
        PreparedAgentRuntime runtime = runtime(
                List.of(tool("missing_agent", "", "allow")),
                List.of(
                        tool("shared", TOOL_ID, "allow"),
                        tool("shared", TOOL_ID, "ask"),
                        tool("missing_skill", null, "allow")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);

        assertThat(policy.decide(agentCall("missing_agent"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(callTool("shared"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
        assertThat(policy.decide(callTool("missing_skill"))).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
    }

    @Test
    void shouldAuthorizePreparedSkillTargetIdentityInsteadOfMutableOuterArguments() {
        PreparedAgentRuntime runtime = runtime(List.of(), List.of(tool("shared", TOOL_ID, "ask")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);
        ToolCall outerCall = callTool("other");
        BeforeToolCallContext trusted = context(outerCall, new PermissionInvocation("CallTool", "shared", TOOL_ID));
        BeforeToolCallContext wrongId =
                context(outerCall, new PermissionInvocation("CallTool", "shared", "tool-wrong"));

        assertThat(policy.decide(trusted)).isEqualTo(RuntimeToolPermissionPolicy.Decision.ASK);
        assertThat(policy.decide(wrongId)).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
    }

    @Test
    void shouldAuthorizePreparedAgentTargetInAgentScopeOnly() {
        PreparedAgentRuntime runtime =
                runtime(List.of(tool("shared", TOOL_ID, "allow")), List.of(tool("shared", TOOL_ID, "deny")));
        RuntimeToolPermissionPolicy policy = RuntimeToolPermissionPolicy.from(runtime);
        ToolCall outerCall = agentCall("shared");
        BeforeToolCallContext trusted = context(outerCall, new PermissionInvocation("shared", "shared", TOOL_ID));
        BeforeToolCallContext wrongId = context(outerCall, new PermissionInvocation("shared", "shared", "tool-wrong"));

        assertThat(policy.decide(trusted)).isEqualTo(RuntimeToolPermissionPolicy.Decision.ALLOW);
        assertThat(policy.decide(wrongId)).isEqualTo(RuntimeToolPermissionPolicy.Decision.DENY);
    }

    @Test
    void shouldDiagnoseMissingBlankUniqueAndAmbiguousBindings() {
        PreparedAgentRuntime missing = runtime(List.of(), List.of(tool("other", "other-id", "allow")));
        PreparedAgentRuntime blank = runtime(List.of(tool("target", " ", "allow")), List.of());
        PreparedAgentRuntime unique = runtime(List.of(tool("target", "target-id", "allow")), List.of());
        PreparedAgentRuntime ambiguous =
                runtime(List.of(tool("target", "first-id", "allow")), List.of(tool("target", "second-id", "allow")));

        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(missing, "target"))
                .isEqualTo(new RuntimeToolPermissionPolicy.BindingDiagnosisDTO(BindingStatus.MISSING, null, 0));
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(blank, "target"))
                .isEqualTo(new RuntimeToolPermissionPolicy.BindingDiagnosisDTO(BindingStatus.MISSING, null, 1));
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(unique, "target"))
                .isEqualTo(new RuntimeToolPermissionPolicy.BindingDiagnosisDTO(BindingStatus.BOUND, "target-id", 1));
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(ambiguous, "target"))
                .isEqualTo(new RuntimeToolPermissionPolicy.BindingDiagnosisDTO(BindingStatus.AMBIGUOUS, null, 2));
    }

    @Test
    void shouldAllowSameToolIdentityAcrossAgentAndSkills() {
        PreparedAgentRuntime runtime = runtimeWithSkills(
                List.of(tool("confirm_with_user", "confirm-id", "allow")),
                List.of(
                        List.of(tool("confirm_with_user", "confirm-id", "allow")),
                        List.of(tool("confirm_with_user", "confirm-id", "allow"))));

        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(runtime, "confirm_with_user"))
                .isEqualTo(new RuntimeToolPermissionPolicy.BindingDiagnosisDTO(BindingStatus.BOUND, "confirm-id", 3));
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(runtime, "confirm_with_user"))
                .contains("confirm-id");
    }

    @Test
    void uniqueAllowedToolIdShouldRejectInvalidIdentityAndPermission() {
        PreparedAgentRuntime denied = runtime(List.of(tool("target", "target-id", "deny")), List.of());
        PreparedAgentRuntime asked = runtime(List.of(tool("target", "target-id", "ask")), List.of());
        PreparedAgentRuntime conflictingPermissions =
                runtime(List.of(tool("target", "target-id", "allow")), List.of(tool("target", "target-id", "ask")));
        PreparedAgentRuntime conflictingIdentities =
                runtime(List.of(tool("target", "target-id", "allow")), List.of(tool("target", "other-id", "allow")));
        PreparedAgentRuntime missingId = runtime(List.of(tool("target", "", "allow")), List.of());

        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(denied, "target"))
                .isEmpty();
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(asked, "target"))
                .isEmpty();
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(conflictingPermissions, "target"))
                .isEmpty();
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(conflictingIdentities, "target"))
                .isEmpty();
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(missingId, "target"))
                .isEmpty();
    }

    private static PreparedAgentRuntime runtime(List<BoundTool> agentTools, List<BoundTool> skillTools) {
        return runtimeWithSkills(agentTools, List.of(skillTools));
    }

    private static PreparedAgentRuntime runtimeWithSkills(
            List<BoundTool> agentTools, List<List<BoundTool>> skillTools) {
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        List<SkillInfo> skills = skillTools.stream()
                .map(tools -> {
                    SkillInfo skill = mock(SkillInfo.class);
                    when(skill.bindingTools()).thenReturn(tools);
                    return skill;
                })
                .toList();
        when(metadata.bindingTools()).thenReturn(agentTools);
        when(runtime.metadata()).thenReturn(metadata);
        when(runtime.skills()).thenReturn(skills);
        return runtime;
    }

    private static BoundTool tool(String name, String permission) {
        return tool(name, TOOL_ID, permission);
    }

    private static BoundTool tool(String name, String id, String permission) {
        return new BoundTool(null, null, id, null, name, permission, null, null);
    }

    private static ToolCall callTool(String tool) {
        return new ToolCall("call-id", "CallTool", Map.of("tool", tool, "args", Map.of()));
    }

    private static ToolCall agentCall(String tool) {
        return new ToolCall("call-id", tool, Map.of());
    }

    private static BeforeToolCallContext context(ToolCall call, PreparedToolInvocation invocation) {
        return new BeforeToolCallContext(null, call, call.arguments(), null, invocation);
    }

    private record PermissionInvocation(String toolName, String authorizationTargetName, String authorizationTargetId)
            implements PreparedToolInvocation {

        @Override
        public String toolCallId() {
            return "call-id";
        }

        @Override
        public Map<String, Object> arguments() {
            return Map.of();
        }

        @Override
        public AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) {
            throw new AssertionError("permission test must not execute the tool");
        }
    }
}
