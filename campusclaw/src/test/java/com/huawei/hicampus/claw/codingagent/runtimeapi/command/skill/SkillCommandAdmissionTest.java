/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SkillCommandAdmissionTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools", "PDF", "pdf_tools", " ", "pdf\n", "skill:pdf", "/pdf"})
    void testRejectsNamesUsingTheSharedStrictRule(String name) {
        RuntimeApiException failure =
                assertThrows(RuntimeApiException.class, () -> new SkillCommandAdmission("agent-a", name));

        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "pdf-tools"})
    void testReturnsTheExactBoundSnapshotWithoutReadingFiles(String name) {
        SkillInfo skill = skill(name);
        PreparedAgentRuntime runtime = runtime("agent-a", "agent-a", true, List.of(skill));

        assertThat(new SkillCommandAdmission("agent-a", name).requireBoundSkill(runtime))
                .isSameAs(skill);
    }

    @Test
    void testEnforcesTheSixtyFourCharacterBoundary() {
        String acceptedName = "a".repeat(64);
        SkillInfo skill = skill(acceptedName);
        var admission = new SkillCommandAdmission("agent-a", acceptedName);

        assertThat(admission.requireBoundSkill(runtime("agent-a", "agent-a", true, List.of(skill))))
                .isSameAs(skill);
        RuntimeApiException failure =
                assertThrows(RuntimeApiException.class, () -> new SkillCommandAdmission("agent-a", acceptedName + "a"));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
    }

    @Test
    void testRejectsAbsentBindingWithoutFallingBackToOrdinaryInput() {
        var admission = new SkillCommandAdmission("agent-a", "pdf");
        PreparedAgentRuntime runtime = runtime("agent-a", "agent-a", true, List.of(skill("pdf-tools")));

        RuntimeApiException failure = assertThrows(RuntimeApiException.class, () -> admission.accept(runtime));

        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_NOT_FOUND);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void testRejectsUnavailableAndWrongAgentSnapshots() {
        var admission = new SkillCommandAdmission("agent-a", "pdf");
        List<PreparedAgentRuntime> invalid = List.of(
                runtime("agent-b", "agent-b", true, List.of(skill("pdf"))),
                runtime("agent-a", "agent-b", true, List.of(skill("pdf"))),
                runtime("agent-a", "agent-a", false, List.of(skill("pdf"))),
                new PreparedAgentRuntime("agent-a", Path.of("/unread"), null, List.of(skill("pdf"))));

        for (PreparedAgentRuntime runtime : invalid) {
            RuntimeApiException failure = assertThrows(RuntimeApiException.class, () -> admission.accept(runtime));
            assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        RuntimeApiException failure = assertThrows(RuntimeApiException.class, () -> admission.accept(null));
        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
    }

    private static PreparedAgentRuntime runtime(
            String agentId, String metadataId, boolean enabled, List<SkillInfo> skills) {
        AgentRuntime metadata = new AgentRuntime(
                List.of("model-a"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                enabled,
                metadataId,
                "agent",
                "System",
                List.of(),
                "v1");
        return new PreparedAgentRuntime(agentId, Path.of("/unread"), metadata, skills);
    }

    private static SkillInfo skill(String name) {
        return new SkillInfo(name, "skill-1", "v1", "description", null, "body", null, null, null, null);
    }
}
