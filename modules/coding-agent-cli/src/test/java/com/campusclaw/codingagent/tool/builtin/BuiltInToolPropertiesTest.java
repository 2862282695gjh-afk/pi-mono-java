/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class BuiltInToolPropertiesTest {

    @Test
    void providesThreeDocumentedDefaultProfiles() throws Exception {
        var properties = new BuiltInToolProperties();

        properties.afterPropertiesSet();

        assertThat(properties.toolsFor(ToolEntryPoint.RUNTIME)).containsExactly(BuiltInToolName.values());
        assertThat(properties.toolsFor(ToolEntryPoint.CRON))
                .containsExactly(
                        BuiltInToolName.READ,
                        BuiltInToolName.FIND,
                        BuiltInToolName.GREP,
                        BuiltInToolName.LS,
                        BuiltInToolName.LIST_MATE_TOOLS,
                        BuiltInToolName.CALL_MATE_TOOL,
                        BuiltInToolName.AGENT);
        assertThat(properties.toolsFor(ToolEntryPoint.CHILD_AGENT))
                .containsExactly(
                        BuiltInToolName.READ,
                        BuiltInToolName.FIND,
                        BuiltInToolName.GREP,
                        BuiltInToolName.LS,
                        BuiltInToolName.LIST_MATE_TOOLS,
                        BuiltInToolName.CALL_MATE_TOOL);
    }

    @Test
    void explicitEmptyProfileReplacesDefault() throws Exception {
        var properties = new BuiltInToolProperties();
        properties.setRuntime(List.of());

        properties.afterPropertiesSet();

        assertThat(properties.toolsFor(ToolEntryPoint.RUNTIME)).isEmpty();
        assertThatThrownBy(() -> properties.getRuntime().add("Read"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownLegacyOrWrongCaseNames() {
        assertInvalid(List.of("Bash"), "Unknown built-in tool name: Bash");
        assertInvalid(List.of("read"), "Unknown built-in tool name: read");
        assertInvalid(List.of("Glob"), "Unknown built-in tool name: Glob");
    }

    @Test
    void rejectsDuplicateNames() {
        var properties = new BuiltInToolProperties();
        properties.setCron(List.of("Read", "Read"));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate built-in tool in cron: Read");
    }

    private static void assertInvalid(List<String> names, String expectedMessage) {
        var properties = new BuiltInToolProperties();
        properties.setChildAgent(names);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }
}
