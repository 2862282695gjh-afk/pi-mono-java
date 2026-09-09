/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证内容块通过 Jackson 绑定时保留严格的类型判别和字符串校验。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/10]
 * @since [br_eCampusCore 26.0.0]
 */
class UserMessageContentRequestVOTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void shouldPreserveTextAndFileValuesThroughUnionDeserialization() throws Exception {
        var text = json.readValue("{\"type\":\"text\",\"text\":\" 请分析 \"}", UserMessageContentRequestVO.class);
        var file = json.readValue(
                "{\"type\":\"file\",\"fileId\":\"0123456789abcdef0123456789abcdef\"}",
                UserMessageContentRequestVO.class);

        assertThat(text).isInstanceOfSatisfying(UserMessageContentRequestVO.TextRequestVO.class, value -> {
            assertThat(value.getType()).isEqualTo("text");
            assertThat(value.getText()).isEqualTo(" 请分析 ");
        });
        assertThat(file).isInstanceOfSatisfying(UserMessageContentRequestVO.FileRequestVO.class, value -> {
            assertThat(value.getType()).isEqualTo("file");
            assertThat(value.getFileId()).isEqualTo("0123456789abcdef0123456789abcdef");
        });
    }

    @Test
    void shouldRejectMismatchedConcreteContentTypes() {
        JsonMappingException textError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue(
                        "{\"type\":\"file\",\"text\":\"question\"}", UserMessageContentRequestVO.TextRequestVO.class));
        JsonMappingException fileError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue(
                        "{\"type\":\"text\",\"fileId\":\"0123456789abcdef0123456789abcdef\"}",
                        UserMessageContentRequestVO.FileRequestVO.class));

        assertThat(textError).hasRootCauseMessage("user message content type does not match fields");
        assertThat(fileError).hasRootCauseMessage("user message content type does not match fields");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "true", "{}", "[]"})
    void shouldRejectNonTextualConcreteContentTypes(String type) {
        String input = "{\"type\":" + type + "}";

        JsonMappingException textError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue(input, UserMessageContentRequestVO.TextRequestVO.class));
        JsonMappingException fileError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue(input, UserMessageContentRequestVO.FileRequestVO.class));

        assertThat(textError).hasRootCauseMessage("user message content fields must be strings");
        assertThat(fileError).hasRootCauseMessage("user message content fields must be strings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "true", "{}", "[]"})
    void shouldRejectNonTextualContentFields(String value) {
        JsonMappingException textError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"type\":\"text\",\"text\":" + value + "}", UserMessageContentRequestVO.class));
        JsonMappingException fileError = assertThrows(
                JsonMappingException.class,
                () -> json.readValue(
                        "{\"type\":\"file\",\"fileId\":" + value + "}", UserMessageContentRequestVO.class));

        assertThat(textError).hasRootCauseMessage("user message content fields must be strings");
        assertThat(fileError).hasRootCauseMessage("user message content fields must be strings");
    }
}
