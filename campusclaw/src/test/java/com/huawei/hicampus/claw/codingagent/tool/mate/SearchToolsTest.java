/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.meta;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.requiredValueSchema;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchTools} 的精确名称发现、安全失败和定义一致性测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
class SearchToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMateToolClient client;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(meta(QUERY_ID, "Query", requiredValueSchema()));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
    }

    @Test
    void shouldPublishFixedExactNameContract() {
        SearchTools tool = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();

        assertThat(tool.name()).isEqualTo("SearchTools");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("required").get(0).asText()).isEqualTo("tool");
        assertThat(tool.parameters().path("properties").fieldNames())
                .toIterable()
                .containsExactly("tool");
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void shouldReturnOneCompleteDefinitionWithoutInternalMetadata() throws Exception {
        SearchTools tool = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();

        JsonNode definition = result(tool, "Query");

        assertThat(definition.fieldNames()).toIterable().containsExactly("name", "description", "inputSchema");
        assertThat(definition.path("name").asText()).isEqualTo("Query");
        assertThat(definition.path("description").asText()).isEqualTo("Query records");
        assertThat(definition.at("/inputSchema/properties/value/type").asText()).isEqualTo("string");
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void shouldUseExactCaseAndHideUnavailableNames() {
        SearchTools tool = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();

        assertCode(tool, "query", ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE);
        assertCode(tool, " ", ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void shouldNotDiscloseToolBoundOnlyThroughHiddenSkill() {
        SkillInfo hidden = customSkill(
                "hidden",
                SKILL_ID,
                "---\nname: hidden\ndescription: Hidden\ndisable-model-invocation: true\n---\nInstructions",
                List.of(binding(QUERY_ID, "Query", "查询", "allow")));
        SearchTools tool = state(List.of(hidden)).createSearchTools();

        assertCode(tool, "Query", ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE);
        assertThat(client.skillListCalls()).isZero();
    }

    @Test
    void shouldFailSafelyWhenMetadataOrSchemaIsUnavailable() {
        SearchTools metadataMissing = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();
        client.bindSkill(SKILL_ID, List.of());

        assertCode(metadataMissing, "Query", ClawConstants.RuntimeApi.TOOL_DEFINITION_UNAVAILABLE_ERROR_CODE);

        client.addTool(new MateToolMeta(QUERY_ID, "Query", "Query records", null, Map.of(), false, "allow"));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
        SearchTools schemaMissing = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();

        assertCode(schemaMissing, "Query", ClawConstants.RuntimeApi.TOOL_DEFINITION_UNAVAILABLE_ERROR_CODE);
    }

    @Test
    void shouldRejectConflictingTrustedNames() {
        String secondId = "tool-22222222222222222222222222222222";
        SkillInfo first = skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")));
        SkillInfo second = customSkill(
                "secondary",
                "skill-cccccccccccccccccccccccccccccccc",
                "---\nname: secondary\ndescription: Secondary\n---\nInstructions",
                List.of(binding(secondId, "Query", "查询", "allow")));
        SearchTools tool = state(List.of(first, second)).createSearchTools();

        assertCode(tool, "Query", ClawConstants.RuntimeApi.TOOL_DEFINITION_CONFLICT_ERROR_CODE);
        assertThat(client.skillListCalls()).isZero();
    }

    @Test
    void shouldRejectInvalidAndOversizedDefinitionsWithoutTruncation() {
        client.addTool(meta(QUERY_ID, "Query", Map.of("$ref", "#/$defs/missing")));
        SearchTools invalid = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();
        assertCode(invalid, "Query", ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE);

        client.addTool(new MateToolMeta(
                QUERY_ID,
                "Query",
                "x".repeat(ClawConstants.Tool.MAX_DEFINITION_RESULT_BYTES),
                requiredValueSchema(),
                Map.of(),
                false,
                "allow"));
        SearchTools oversized = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();
        assertCode(oversized, "Query", ClawConstants.RuntimeApi.TOOL_DEFINITION_TOO_LARGE_ERROR_CODE);
    }

    @Test
    void shouldReuseDisclosedDefinitionForLaterCallValidation() throws Exception {
        var trusted = binding(QUERY_ID, "Query", "查询", "allow");
        client.bindAgent(MateToolTestSupport.AGENT_ID, List.of(QUERY_ID));
        MateToolSessionState state = new MateToolsetFactory(client)
                .createSession(runtime(List.of(trusted), List.of(skill(List.of(trusted)))), MateCredentials.empty());

        JsonNode disclosed = result(state.createSearchTools(), "Query");
        client.addTool(meta(QUERY_ID, "Query", Map.of("type", "object", "required", List.of("changed"))));
        var proxy = state.createAgentToolAdapters().getFirst();
        state.createCallTool().execute("call", Map.of("tool", "Query", "args", Map.of("value", "same")), null, null);

        assertThat(proxy.parameters()).isEqualTo(disclosed.path("inputSchema"));
        assertThat(disclosed.at("/inputSchema/required/0").asText()).isEqualTo("value");
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    @Test
    void shouldNotCreateBusinessProxyOrActivationState() throws Exception {
        MateToolSessionState state = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))));

        assertThat(state.createAgentToolAdapters()).isEmpty();
        result(state.createSearchTools(), "Query");

        assertThat(state.createAgentToolAdapters()).isEmpty();
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void shouldShareOneDefinitionAcrossAgentProxySearchAndCall() throws Exception {
        var trusted = binding(QUERY_ID, "Query", "查询", "allow");
        client.bindAgent(MateToolTestSupport.AGENT_ID, List.of(QUERY_ID));
        MateToolSessionState state = new MateToolsetFactory(client)
                .createSession(runtime(List.of(trusted), List.of(skill(List.of(trusted)))), MateCredentials.empty());

        var proxy = state.createAgentToolAdapters().getFirst();
        client.addTool(meta(QUERY_ID, "Query", Map.of("type", "object", "required", List.of("changed"))));
        JsonNode disclosed = result(state.createSearchTools(), "Query");
        state.createCallTool().execute("call", Map.of("tool", "Query", "args", Map.of("value", "same")), null, null);

        assertThat(proxy.parameters()).isEqualTo(disclosed.path("inputSchema"));
        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.skillListCalls()).isZero();
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    @Test
    void shouldStopBeforeResolutionWhenCancelled() {
        SearchTools tool = state(List.of(skill(List.of(binding(QUERY_ID, "Query", "查询", "allow")))))
                .createSearchTools();
        CancellationToken signal = new CancellationToken();
        signal.cancel();

        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", "Query"), signal, null))
                .isInstanceOf(InterruptedException.class);
        assertThat(client.skillListCalls()).isZero();
    }

    private MateToolSessionState state(List<SkillInfo> skills) {
        return new MateToolsetFactory(client).createSession(runtime(List.of(), skills), MateCredentials.empty());
    }

    private JsonNode result(SearchTools tool, String name) throws Exception {
        TextContent content = (TextContent)
                tool.execute("call", Map.of("tool", name), null, null).content().getFirst();
        return MAPPER.readTree(content.text());
    }

    private void assertCode(SearchTools tool, String name, String expected) {
        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", name), null, null))
                .isInstanceOfSatisfying(StableErrorCode.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(expected));
    }

    private static SkillInfo customSkill(
            String name,
            String id,
            String content,
            List<com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool> tools) {
        return new SkillInfo(name, id, "1.0.0", name, null, content, tools, List.of(), List.of(), List.of());
    }
}
