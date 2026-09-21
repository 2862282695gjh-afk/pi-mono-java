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
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingStatus;

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

    @Test
    void shouldDiagnoseMissingBlankUniqueAndAmbiguousBindings() {
        PreparedAgentRuntime missing = runtime(List.of(), List.of(tool("other", "other-id", "allow")));
        PreparedAgentRuntime blank = runtime(List.of(tool("target", " ", "allow")), List.of());
        PreparedAgentRuntime unique = runtime(List.of(tool("target", "target-id", "allow")), List.of());
        PreparedAgentRuntime ambiguous =
                runtime(List.of(tool("target", "first-id", "allow")), List.of(tool("target", "second-id", "allow")));

        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(missing, "target"))
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.status()).isEqualTo(BindingStatus.MISSING);
                    assertThat(diagnosis.toolId()).isNull();
                    assertThat(diagnosis.matchingCount()).isZero();
                });
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(blank, "target")).satisfies(diagnosis -> {
            assertThat(diagnosis.status()).isEqualTo(BindingStatus.MISSING);
            assertThat(diagnosis.matchingCount()).isEqualTo(1);
        });
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(unique, "target"))
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.status()).isEqualTo(BindingStatus.BOUND);
                    assertThat(diagnosis.toolId()).isEqualTo("target-id");
                    assertThat(diagnosis.matchingCount()).isEqualTo(1);
                });
        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(ambiguous, "target"))
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.status()).isEqualTo(BindingStatus.AMBIGUOUS);
                    assertThat(diagnosis.toolId()).isNull();
                    assertThat(diagnosis.matchingCount()).isEqualTo(2);
                });
    }

    @Test
    void shouldAllowSameToolIdentityAcrossAgentAndSkill() {
        PreparedAgentRuntime runtime = runtime(
                List.of(tool("confirm_with_user", "confirm-id", "allow")),
                List.of(tool("confirm_with_user", "confirm-id", "allow")));

        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(runtime, "confirm_with_user"))
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.status()).isEqualTo(BindingStatus.BOUND);
                    assertThat(diagnosis.toolId()).isEqualTo("confirm-id");
                    assertThat(diagnosis.matchingCount()).isEqualTo(2);
                });
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(runtime, "confirm_with_user"))
                .contains("confirm-id");
    }

    @Test
    void shouldAllowSameToolIdentityAcrossMultipleSkills() {
        PreparedAgentRuntime runtime = runtimeWithSkills(
                List.of(),
                List.of(
                        List.of(tool("confirm_with_user", "confirm-id", "allow")),
                        List.of(tool("confirm_with_user", "confirm-id", "allow"))));

        assertThat(RuntimeToolPermissionPolicy.diagnoseBinding(runtime, "confirm_with_user"))
                .satisfies(diagnosis -> {
                    assertThat(diagnosis.status()).isEqualTo(BindingStatus.BOUND);
                    assertThat(diagnosis.toolId()).isEqualTo("confirm-id");
                    assertThat(diagnosis.matchingCount()).isEqualTo(2);
                });
        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(runtime, "confirm_with_user"))
                .contains("confirm-id");
    }

    @Test
    void uniqueAllowedToolIdShouldRetainExistingAdmissionBehavior() {
        PreparedAgentRuntime allowed = runtime(List.of(tool("target", "target-id", "allow")), List.of());
        PreparedAgentRuntime denied = runtime(List.of(tool("target", "target-id", "deny")), List.of());
        PreparedAgentRuntime asked = runtime(List.of(tool("target", "target-id", "ask")), List.of());
        PreparedAgentRuntime conflictingPermissions =
                runtime(List.of(tool("target", "target-id", "allow")), List.of(tool("target", "target-id", "ask")));
        PreparedAgentRuntime conflictingIdentities =
                runtime(List.of(tool("target", "target-id", "allow")), List.of(tool("target", "other-id", "allow")));
        PreparedAgentRuntime missingId = runtime(List.of(tool("target", "", "allow")), List.of());

        assertThat(RuntimeToolPermissionPolicy.uniqueAllowedToolId(allowed, "target"))
                .contains("target-id");
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
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        AgentRuntime metadata = mock(AgentRuntime.class);
        SkillInfo skill = mock(SkillInfo.class);
        when(metadata.bindingTools()).thenReturn(agentTools);
        when(skill.bindingTools()).thenReturn(skillTools);
        when(runtime.metadata()).thenReturn(metadata);
        when(runtime.skills()).thenReturn(List.of(skill));
        return runtime;
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
        return tool(name, "tool-id", permission);
    }

    private static BoundTool tool(String name, String id, String permission) {
        return new BoundTool(null, null, id, null, name, permission, null, null);
    }

    private static ToolCall call(String tool) {
        return new ToolCall("call-id", "CallMateTool", Map.of("tool", tool, "args", Map.of()));
    }
}
