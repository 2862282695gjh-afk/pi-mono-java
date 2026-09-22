/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BuiltInToolPropertiesTest {

    @Test
    void shouldProvideThreeDocumentedProfilesWhenUsingDefaults() throws Exception {
        var properties = new BuiltInToolProperties();

        properties.afterPropertiesSet();

        assertThat(BuiltInToolName.values())
                .extracting(BuiltInToolName::externalName)
                .containsExactly(
                        "Read",
                        "Find",
                        "Grep",
                        "Ls",
                        "Cron",
                        "Skill",
                        "SearchTools",
                        "CallTool",
                        "Agent",
                        "ExitPlanMode",
                        "TodoUpdate",
                        "AskUserQuestion");
        assertThat(properties.toolsFor(ToolEntryPoint.RUNTIME)).containsExactly(BuiltInToolName.values());
        assertThat(properties.toolsFor(ToolEntryPoint.CRON))
                .containsExactly(
                        BuiltInToolName.READ,
                        BuiltInToolName.FIND,
                        BuiltInToolName.GREP,
                        BuiltInToolName.LS,
                        BuiltInToolName.SKILL,
                        BuiltInToolName.SEARCH_TOOLS,
                        BuiltInToolName.CALL_TOOL,
                        BuiltInToolName.AGENT);
        assertThat(properties.toolsFor(ToolEntryPoint.CHILD_AGENT))
                .containsExactly(
                        BuiltInToolName.READ,
                        BuiltInToolName.FIND,
                        BuiltInToolName.GREP,
                        BuiltInToolName.LS,
                        BuiltInToolName.SKILL,
                        BuiltInToolName.SEARCH_TOOLS,
                        BuiltInToolName.CALL_TOOL);
    }

    @Test
    void shouldReplaceDefaultWhenProfileIsExplicitlyEmpty() throws Exception {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of());

        properties.afterPropertiesSet();

        assertThat(properties.toolsFor(ToolEntryPoint.RUNTIME)).isEmpty();
        assertThrows(
                UnsupportedOperationException.class,
                () -> properties.getRuntime().add("Read"));
    }

    @ParameterizedTest(name = "{0} rejects retired name {1}")
    @MethodSource("retiredProfileNames")
    void shouldRejectRetiredNameForEveryProfile(ToolEntryPoint entryPoint, String name) {
        var properties = propertiesWith(entryPoint, name);

        var thrown = assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
        assertThat(thrown).hasMessage("Unknown built-in tool name: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "Glob"})
    void shouldRejectOtherUnknownOrWrongCaseNames(String name) {
        var properties = propertiesWith(ToolEntryPoint.RUNTIME, name);

        var thrown = assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
        assertThat(thrown).hasMessage("Unknown built-in tool name: " + name);
    }

    @Test
    void shouldRejectNamesWhenProfileContainsDuplicates() {
        var properties = new BuiltInToolProperties();
        properties.setCron(List.of("Read", "Read"));

        var thrown = assertThrows(IllegalArgumentException.class, properties::afterPropertiesSet);
        assertThat(thrown).hasMessage("Duplicate built-in tool in cron: Read");
    }

    private static Stream<Arguments> retiredProfileNames() {
        return Stream.of(ToolEntryPoint.values()).flatMap(entryPoint -> Stream.of(
                        "Bash", "Edit", "Write", "ListMateTools", "CallMateTool", "bash", "edit", "write")
                .map(name -> Arguments.of(entryPoint, name)));
    }

    private static BuiltInToolProperties propertiesWith(ToolEntryPoint entryPoint, String name) {
        var properties = new BuiltInToolProperties();
        switch (entryPoint) {
            case RUNTIME -> properties.setRuntime(List.of(name));
            case CRON -> properties.setCron(List.of(name));
            case CHILD_AGENT -> properties.setChildAgent(List.of(name));
        }
        return properties;
    }
}
