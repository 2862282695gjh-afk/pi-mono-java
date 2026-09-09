/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class CommandRequestVOTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @Test
    void shouldSelectRequestTypesAndRetainAllDeclaredFields() throws Exception {
        var builtin = json.readValue("{\"arguments\":\" model-a \",\"name\":\"model\"}", CommandRequestVO.class);
        var skill = json.readValue(
                "{\"fileIds\":[\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                        + "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"],"
                        + "\"arguments\":\" 请分析 \",\"name\":\"skill:pdf\"}",
                CommandRequestVO.class);
        assertThat(builtin).isExactlyInstanceOf(BuiltinCommandRequestVO.class);
        assertThat(builtin.getName()).isEqualTo("model");
        assertThat(builtin.getArguments()).isEqualTo(" model-a ");
        assertThat(skill).isExactlyInstanceOf(SkillCommandRequestVO.class);
        assertThat(skill.getName()).isEqualTo("skill:pdf");
        assertThat(skill.getArguments()).isEqualTo(" 请分析 ");
        assertThat(((SkillCommandRequestVO) skill).getFileIds())
                .containsExactly("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(VALIDATION.getValidator().validate(skill)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"help", "skill:pdf"})
    void shouldDeserializeConcreteTypesWithoutRecursingThroughTheInterface(String name) throws Exception {
        Class<? extends CommandRequestVO> type =
                name.equals("help") ? BuiltinCommandRequestVO.class : SkillCommandRequestVO.class;
        var value = json.readValue("{\"name\":\"" + name + "\"}", type);
        assertThat(value).isExactlyInstanceOf(type);
        assertThat(value.getName()).isEqualTo(name);
        assertThat(value.getArguments()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[{}]", "0", "true", "\"help\""})
    void shouldRejectEveryNonObjectRequest(String input) {
        var failure = assertThrows(JsonMappingException.class, () -> json.readValue(input, CommandRequestVO.class));
        assertThat(failure.getOriginalMessage()).isEqualTo("command request must be an object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}"})
    void shouldLeaveRequiredNameValidationToJakarta(String input) throws Exception {
        var request = json.readValue(input, CommandRequestVO.class);
        assertThat(VALIDATION.getValidator().validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .contains("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "true", "[]", "{}"})
    void shouldRejectCoercionOfNamesAndBothArgumentTypes(String value) {
        assertThrows(
                JsonMappingException.class, () -> json.readValue("{\"name\":" + value + "}", CommandRequestVO.class));
        assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"help\",\"arguments\":" + value + "}", CommandRequestVO.class));
        assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"skill:pdf\",\"arguments\":" + value + "}", CommandRequestVO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[\"file-a\"]"})
    void shouldRejectBuiltinAttachmentsEvenWhenNull(String value) {
        var failure = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"help\",\"fileIds\":" + value + "}", CommandRequestVO.class));
        assertThat(failure).hasRootCauseMessage("unknown builtin command field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "message", "body", "skillId", "version", "path", "unknown"})
    void shouldRejectUnknownFieldsForBothKindsWithAPermissiveMapper(String field) {
        assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"help\",\"" + field + "\":null}", CommandRequestVO.class));
        var failure = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"skill:pdf\",\"" + field + "\":null}", CommandRequestVO.class));
        assertThat(failure).hasRootCauseMessage("unknown skill command field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "true", "{}", "\"file-a\"", "[null]", "[0]", "[true]", "[{}]", "[[]]"})
    void shouldRejectInvalidAttachmentTypes(String value) {
        assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"skill:pdf\",\"fileIds\":" + value + "}", CommandRequestVO.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"skill:pdf\"}", "{\"name\":\"skill:pdf\",\"arguments\":null,\"fileIds\":null}"})
    void shouldPreserveNullDefaultsForTheExecutionService(String input) throws Exception {
        var request = (SkillCommandRequestVO) json.readValue(input, CommandRequestVO.class);
        assertThat(request.getArguments()).isNull();
        assertThat(request.getFileIds()).isNull();
        assertThat(VALIDATION.getValidator().validate(request)).isEmpty();
        assertThat(SkillCommandRequestVO.builder().name("skill:pdf").build()).isEqualTo(request);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "pdf",
                "/skill:pdf",
                "skill:",
                "skill:-pdf",
                "skill:pdf-",
                "skill:pdf--tools",
                "skill:PDF",
                "skill:pdf_tools",
                "skill:pdf ",
                "skill:pdf\n",
                "other:pdf",
                "skill:skill:pdf"
            })
    void shouldApplyTheSharedStrictNameRuleToSkillRequests(String name) {
        var request = SkillCommandRequestVO.builder().name(name).build();
        assertThat(VALIDATION.getValidator().validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .contains("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "pdf-tools"})
    void shouldShareThePackageNameGrammarWithoutApplyingTheBuiltinLengthToThePrefix(String name) {
        var request = SkillCommandRequestVO.builder().name("skill:" + name).build();
        assertThat(ClawConstants.Skill.isValidName(name)).isTrue();
        assertThat(ClawConstants.Skill.COMMAND_NAME_PATTERN
                        .matcher(request.getName())
                        .matches())
                .isTrue();
        assertThat(VALIDATION.getValidator().validate(request)).isEmpty();
    }

    @Test
    void shouldValidateExactNameAndUtf16ArgumentLimitsBeforeNormalization() {
        var allowed = new SkillCommandRequestVO("skill:" + "a".repeat(64), "😀".repeat(131072), List.of());
        assertThat(VALIDATION.getValidator().validate(allowed)).isEmpty();
        var rejected = new SkillCommandRequestVO(allowed.getName() + "a", " ".repeat(262145), List.of());
        assertThat(VALIDATION.getValidator().validate(rejected))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "arguments");
    }

    @Test
    void shouldValidateEventAttachmentCountAndIdentifierFormat() {
        var files = IntStream.range(0, 4)
                .mapToObj(index -> "%032x".formatted(index))
                .toList();
        var allowed = new SkillCommandRequestVO("skill:pdf", null, files);
        assertThat(VALIDATION.getValidator().validate(allowed)).isEmpty();
        var overflow = new SkillCommandRequestVO(
                "skill:pdf", null, java.util.Collections.nCopies(5, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertThat(VALIDATION.getValidator().validate(overflow))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactly("fileIds");
        var invalidItems = new SkillCommandRequestVO("skill:pdf", null, Arrays.asList("file-a", "外部文件", null));
        assertThat(VALIDATION.getValidator().validate(invalidItems)).hasSize(3);
    }

    @Test
    void shouldRetainBlankArgumentsAndDuplicateFilesForServiceBusinessValidation() throws Exception {
        var request = (SkillCommandRequestVO) json.readValue(
                "{\"name\":\"skill:pdf\",\"arguments\":\"  \","
                        + "\"fileIds\":[\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                        + "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"]}",
                CommandRequestVO.class);
        assertThat(request.getArguments()).isEqualTo("  ");
        assertThat(request.getFileIds())
                .containsExactly("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(VALIDATION.getValidator().validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"other:pdf", "/help", "/skill:pdf", "Skill:pdf", "skillish:pdf"})
    void shouldRejectUnsupportedNamespacesAfterSelectingTheBuiltinConstraints(String name) throws Exception {
        var request = json.readValue("{\"name\":\"" + name + "\"}", CommandRequestVO.class);
        assertThat(request).isExactlyInstanceOf(BuiltinCommandRequestVO.class);
        assertThat(VALIDATION.getValidator().validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .contains("name");
    }
}
