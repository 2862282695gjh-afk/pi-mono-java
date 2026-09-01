/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.agent.tool.AfterToolCallHandler;
import com.huawei.hicampus.claw.agent.tool.AfterToolCallResult;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallHandler;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.ai.types.TextContent;

import org.junit.jupiter.api.Test;

class AgentSessionHookChainTest {

    @Test
    void executesImmutableBeforeHooksInOrderAndStopsOnBlock() throws Exception {
        List<String> calls = new ArrayList<>();
        BeforeToolCallHandler first = context -> {
            calls.add("first");
            return BeforeToolCallResult.allow();
        };
        BeforeToolCallHandler second = context -> {
            calls.add("second");
            return BeforeToolCallResult.block("blocked");
        };
        BeforeToolCallHandler third = context -> {
            calls.add("third");
            return BeforeToolCallResult.allow();
        };

        BeforeToolCallResult result =
                AgentSessionHookChain.before(List.of(first, second, third)).handle(null);

        assertThat(calls).containsExactly("first", "second");
        assertThat(result.reason()).isEqualTo("blocked");
    }

    @Test
    void executesAfterHooksInOrderAndCombinesOverrides() throws Exception {
        List<String> calls = new ArrayList<>();
        AfterToolCallHandler first = context -> {
            calls.add("first");
            return new AfterToolCallResult(List.of(new TextContent("override")), null, null);
        };
        AfterToolCallHandler second = context -> {
            calls.add("second");
            return new AfterToolCallResult(null, "details", true);
        };

        AfterToolCallResult result =
                AgentSessionHookChain.after(List.of(first, second)).handle(null);

        assertThat(calls).containsExactly("first", "second");
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("override");
        assertThat(result.details()).isEqualTo("details");
        assertThat(result.isError()).isTrue();
    }
}
