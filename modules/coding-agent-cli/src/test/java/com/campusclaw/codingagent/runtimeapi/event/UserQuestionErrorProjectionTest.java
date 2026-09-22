/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentToolResultResponseVO;
import com.campusclaw.codingagent.tool.question.UserQuestionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 验证细分问答失败码从工具异常经过实时投影与历史解码后保持一致。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/18]
 * @since [br_eCampusCore 26.0.0]
 */
class UserQuestionErrorProjectionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("questionErrorCodes")
    void shouldPreserveSpecificCodeAndLocalizedTextAcrossPipelineAndHistory(String code) {
        var call = new ToolCall("call-diagnostic", "CallTool", Map.of("tool", "confirm_with_user"));
        var result = new ToolExecutionPipeline()
                .execute(
                        new FailingTool(code),
                        call,
                        call.arguments(),
                        new AgentContext(),
                        new CancellationToken(),
                        null);
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        var factory = new RuntimeCommittedEventFactory(MAPPER, messages);
        var projection = new CommittedEventProjection(MAPPER);
        var encoder = new RuntimeEventEncoder(projection, MAPPER);
        RuntimeEntryDTO entry = new RuntimeEntryDTO();
        entry.setSessionId("session-diagnostic");
        entry.setId("entry-diagnostic");
        entry.setType("tool.result");
        entry.setTimestamp(OffsetDateTime.parse("2026-09-18T01:00:00Z"));
        entry.setPayload("{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.details()).isEqualTo(Map.of("errorCode", code));
        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            String localized = messages.getMessage(code, null, locale);
            assertThat(localized).isNotBlank().isNotEqualTo(code);
            var event = factory.agentToolResult(entry, "event-diagnostic", result, "source-diagnostic", locale);
            var frame = encoder.committed(event);
            var history = (AgentToolResultResponseVO) projection.project(event).getDetails();
            assertThat(frame.getData()).containsEntry("errorCode", code).containsEntry("isError", true);
            assertThat(frame.getData().get("content")).isEqualTo(List.of(Map.of("type", "text", "text", localized)));
            assertThat(history.getErrorCode()).isEqualTo(code);
            assertThat(history.getContent().getFirst().getText()).isEqualTo(localized);
            assertThat(event.getPayload()).doesNotContain("private-discovery-cause");
        }
    }

    private static Stream<String> questionErrorCodes() {
        return Stream.of(
                "USER_QUESTION_ENTRY_UNSUPPORTED",
                "USER_QUESTION_PORT_MISSING",
                "USER_QUESTION_ADAPTER_UNBOUND",
                "USER_QUESTION_BINDING_MISSING",
                "USER_QUESTION_BINDING_AMBIGUOUS",
                "USER_QUESTION_PERMISSION_DENIED",
                "USER_QUESTION_PERMISSION_REQUIRED",
                "USER_QUESTION_DISCOVERY_FAILED",
                "USER_QUESTION_TOOL_NOT_FOUND",
                "USER_QUESTION_TOOL_ID_MISMATCH",
                "USER_QUESTION_PREPARATION_CONFLICT",
                "USER_QUESTION_NOT_PREPARED",
                "USER_QUESTION_CALL_MISMATCH",
                "USER_QUESTION_ANSWER_INVALID",
                "USER_QUESTION_MULTIPLE_TOOL_CALLS",
                "USER_QUESTION_CALL_NOT_FINALIZED",
                "USER_QUESTION_PENDING_CONFLICT",
                "USER_QUESTION_WAIT_MISSING");
    }

    private record FailingTool(String code) implements AgentTool {
        @Override
        public String name() {
            return "CallTool";
        }

        @Override
        public String label() {
            return "Call Tool";
        }

        @Override
        public String description() {
            return "Diagnostic fixture";
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            throw new UserQuestionException(code, new IllegalStateException("private-discovery-cause"));
        }
    }
}
