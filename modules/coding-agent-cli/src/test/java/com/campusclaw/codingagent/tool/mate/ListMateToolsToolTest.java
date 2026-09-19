/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ListMateToolsTool} 的实时作用域发现和稳定 JSON 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class ListMateToolsToolTest {

    private static final String QUERY_ID = "tool-11111111111111111111111111111111";

    private static final MateCredentials CREDENTIALS =
            MateCredentials.appKey("caller-1", "app-key-1", "access-token-1");

    private MockMateToolClient client;

    private MateToolSessionState state;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta(
                QUERY_ID, "Query", "Query records", Map.of("type", "object"), Map.of("hidden", true), true, "allow"));
        client.bindAgent("agent-1", List.of(QUERY_ID));
        client.bindSkill("skill-1", List.of(QUERY_ID));
        state = new MateToolsetFactory(client).createSession("agent-1", Map.of("research", "skill-1"), CREDENTIALS);
    }

    @Test
    void shouldPublishPascalCaseParallelContract() {
        ListMateToolsTool tool = state.createListTool();

        assertThat(tool.name()).isEqualTo("ListMateTools");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("properties").has("skillName")).isTrue();
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void shouldReturnStableAgentJsonWithoutInternalMetadata() throws Exception {
        var result = state.createListTool().execute("call", Map.of(), null, null);
        String json = ((TextContent) result.content().get(0)).text();

        assertThat(json)
                .isEqualTo("{\"scope\":{\"type\":\"agent\"},\"tools\":[{\"name\":\"Query\","
                        + "\"description\":\"Query records\",\"inputSchema\":{\"type\":\"object\"}}]}");
        assertThat(json).doesNotContain(QUERY_ID, "permission", "outputSchema", "isConcurrencySafe");
        assertThat(client.agentListCalls()).isEqualTo(1);
    }

    @Test
    void shouldResolveOnlyDirectSkillName() throws Exception {
        var result = state.createListTool().execute("call", Map.of("skillName", "research"), null, null);

        assertThat(((TextContent) result.content().get(0)).text())
                .startsWith("{\"scope\":{\"type\":\"skill\",\"name\":\"research\"}");
        assertThat(client.lastListSkillId()).isEqualTo("skill-1");
        assertThatThrownBy(() -> state.createListTool().execute("call", Map.of("skillName", "unknown"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown directly bound Skill");
    }

    @Test
    void shouldSortInputSchemaMapKeysForStableJson() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("zeta", Map.of("type", "string"));
        schema.put("alpha", Map.of("type", "number"));
        client.addTool(new MateToolMeta(QUERY_ID, "Query", "Query records", schema, Map.of(), true, "allow"));

        var result = state.createListTool().execute("call", Map.of(), null, null);
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json)
                .contains("\"inputSchema\":{\"alpha\":{\"type\":\"number\"}," + "\"zeta\":{\"type\":\"string\"}}");
    }
}
