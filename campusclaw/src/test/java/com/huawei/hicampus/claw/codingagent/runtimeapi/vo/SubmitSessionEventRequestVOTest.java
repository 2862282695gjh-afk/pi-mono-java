/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/**
 * Session Events v2 三类联合请求的严格解析与边界校验测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class SubmitSessionEventRequestVOTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @Test
    void shouldSelectAllThreeUserEventTypesAndPreserveDeclaredValues() throws Exception {
        var message = read("{\"event\":{\"type\":\"user.message\",\"content\":["
                + "{\"type\":\"text\",\"text\":\" 请分析 \"},"
                + "{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\"}]}}");
        var interrupt = read("{\"event\":{\"type\":\"user.interrupt\",\"targetEventId\":\"event-root\"}}");
        var confirmation = read("{\"event\":{\"type\":\"user.tool_confirmation\","
                + "\"toolCallId\":\"call-1\",\"result\":\"deny\",\"denyMessage\":\" 不执行 \"}}");

        assertThat(message.getEvent()).isExactlyInstanceOf(UserMessageEventRequestVO.class);
        var blocks = ((UserMessageEventRequestVO) message.getEvent()).getContent();
        assertThat(((UserMessageContentRequestVO.TextRequestVO) blocks.getFirst()).getText())
                .isEqualTo(" 请分析 ");
        assertThat(interrupt.getEvent()).isEqualTo(interrupt("event-root"));
        assertThat(confirmation.getEvent()).isEqualTo(confirmation("call-1", "deny", " 不执行 "));
        assertThat(VALIDATION.getValidator().validate(message)).isEmpty();
        assertThat(VALIDATION.getValidator().validate(interrupt)).isEmpty();
        assertThat(VALIDATION.getValidator().validate(confirmation)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{}",
                "{\"event\":null}",
                "{\"event\":[]}",
                "{\"event\":{\"type\":null}}",
                "{\"event\":{\"type\":\"user.other\"}}",
                "{\"event\":{\"type\":\"user.interrupt\",\"targetEventId\":0}}",
                "{\"event\":{\"type\":\"user.interrupt\",\"targetEventId\":\"root\"},\"extra\":0}"
            })
    void shouldRejectNonObjectsNullsUnsupportedTypesAndUnknownEnvelopeFields(String input) {
        JsonMappingException error = assertThrows(JsonMappingException.class, () -> read(input));

        assertThat(error.getOriginalMessage()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"event\":{\"type\":\"user.message\",\"content\":null}}",
                "{\"event\":{\"type\":\"user.message\",\"content\":[0]}}",
                "{\"event\":{\"type\":\"user.message\",\"content\":[{\"type\":\"image\"}]}}",
                "{\"event\":{\"type\":\"user.message\",\"content\":[" + "{\"type\":\"text\",\"text\":null}]}}",
                "{\"event\":{\"type\":\"user.message\",\"content\":["
                        + "{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\",\"text\":\"x\"}]}}"
            })
    void shouldRejectInvalidContentShapesBeforeServiceDispatch(String input) {
        JsonMappingException error = assertThrows(JsonMappingException.class, () -> read(input));

        assertThat(error.getOriginalMessage()).isNotBlank();
    }

    @Test
    void shouldValidateMessageOrderDistinctFilesAndUtf16TextLimits() throws Exception {
        var textAfterFile = read(messageJson("{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\"},"
                + "{\"type\":\"text\",\"text\":\"分析\"}"));
        var duplicateFile = read(messageJson("{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\"},"
                + "{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\"}"));
        var blankText = read(messageJson("{\"type\":\"text\",\"text\":\"  \"}"));
        var invalidFile = read(messageJson("{\"type\":\"file\",\"fileId\":\"file-1\"}"));
        var longText = read(messageJson("{\"type\":\"text\",\"text\":\"" + "😀".repeat(131_073) + "\"}"));
        String fiveFiles = IntStream.range(0, 5)
                .mapToObj(index -> "{\"type\":\"file\",\"fileId\":\"" + "%032x".formatted(index) + "\"}")
                .collect(Collectors.joining(","));
        var tooManyFiles = read(messageJson(fiveFiles));

        assertThat(validationPaths(textAfterFile)).contains("event.contentOrderValid");
        assertThat(validationPaths(duplicateFile)).contains("event.contentOrderValid");
        assertThat(validationPaths(blankText)).contains("event.content[0].text");
        assertThat(validationPaths(invalidFile)).contains("event.content[0].fileId");
        assertThat(validationPaths(longText)).contains("event.content[0].text");
        assertThat(validationPaths(tooManyFiles)).contains("event.contentOrderValid");
    }

    @Test
    void shouldValidateConfirmationDecisionAndDenyMessageRules() throws Exception {
        var allowWithMessage = read("{\"event\":{\"type\":\"user.tool_confirmation\","
                + "\"toolCallId\":\"call-1\",\"result\":\"allow\",\"denyMessage\":\"no\"}}");
        var denyWithBlank = read("{\"event\":{\"type\":\"user.tool_confirmation\","
                + "\"toolCallId\":\"call-1\",\"result\":\"deny\",\"denyMessage\":\"  \"}}");
        String overflow = "x".repeat(4097);
        var denyOverflow = read("{\"event\":{\"type\":\"user.tool_confirmation\","
                + "\"toolCallId\":\"call-1\",\"result\":\"deny\",\"denyMessage\":\"" + overflow + "\"}}");

        assertThat(validationPaths(allowWithMessage)).contains("event.denyMessageValid");
        assertThat(validationPaths(denyWithBlank)).contains("event.denyMessageValid");
        assertThat(validationPaths(denyOverflow)).contains("event.denyMessage");
    }

    @Test
    void shouldValidateDirectlyConstructedConfirmationResult() {
        var unsupported = new SubmitSessionEventRequestVO(confirmation("call-1", "unexpected", null));

        assertThat(validationPaths(unsupported)).contains("event.result");
    }

    private SubmitSessionEventRequestVO read(String input) throws JsonProcessingException {
        return json.readValue(input, SubmitSessionEventRequestVO.class);
    }

    private static String messageJson(String blocks) {
        return "{\"event\":{\"type\":\"user.message\",\"content\":[" + blocks + "]}}";
    }

    private static List<String> validationPaths(SubmitSessionEventRequestVO request) {
        return VALIDATION.getValidator().validate(request).stream()
                .map(value -> value.getPropertyPath().toString())
                .toList();
    }

    private static UserInterruptEventRequestVO interrupt(String targetEventId) {
        var request = new UserInterruptEventRequestVO();
        request.setTargetEventId(targetEventId);
        return request;
    }

    private static UserToolConfirmationEventRequestVO confirmation(
            String toolCallId, String result, String denyMessage) {
        var request = new UserToolConfirmationEventRequestVO();
        request.setToolCallId(toolCallId);
        request.setResult(result);
        request.setDenyMessage(denyMessage);
        return request;
    }
}
