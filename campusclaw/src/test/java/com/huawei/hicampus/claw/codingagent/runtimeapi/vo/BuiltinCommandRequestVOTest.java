/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class BuiltinCommandRequestVOTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/help", "Help", "skill:pdf", "bad_name", "a--b", "-bad", "bad-", "help "})
    void shouldApplyRequiredStrictGrammarOnlyToTheBuiltinRequest(String name) {
        var request = BuiltinCommandRequestVO.builder().name(name).build();
        assertThat(VALIDATION.getValidator().validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void shouldValidateNameAndArgumentsAtExactUtf16Boundaries() {
        var allowed = new BuiltinCommandRequestVO("a".repeat(64), "😀".repeat(131072));
        assertThat(VALIDATION.getValidator().validate(allowed)).isEmpty();
        var tooLong = new BuiltinCommandRequestVO(allowed.getName() + "a", allowed.getArguments() + "x");
        assertThat(VALIDATION.getValidator().validate(tooLong))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "arguments");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "true", "[]", "{}"})
    void shouldRejectJsonCoercionForBothFields(String value) {
        var nameFailure = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":" + value + "}", BuiltinCommandRequestVO.class));
        var argumentsFailure = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"help\",\"arguments\":" + value + "}", BuiltinCommandRequestVO.class));
        assertThat(nameFailure).hasRootCauseMessage("builtin command fields must be strings");
        assertThat(argumentsFailure).hasRootCauseMessage("builtin command fields must be strings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fileIds", "commandId", "unknown"})
    void shouldRejectUnknownFieldsEvenWhenTheMapperIgnoresThemGlobally(String field) {
        var failure = assertThrows(
                JsonMappingException.class,
                () -> json.readValue("{\"name\":\"help\",\"" + field + "\":null}", BuiltinCommandRequestVO.class));
        assertThat(failure).hasRootCauseMessage("unknown builtin command field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"help\"}", "{\"name\":\"help\",\"arguments\":null}"})
    void shouldLeaveOmittedAndNullArgumentsForServiceNormalization(String input) throws Exception {
        var request = json.readValue(input, BuiltinCommandRequestVO.class);
        assertThat(request.getArguments()).isNull();
        assertThat(VALIDATION.getValidator().validate(request)).isEmpty();
        assertThat(BuiltinCommandRequestVO.builder().name("help").build().getArguments())
                .isNull();
    }

    @Test
    void shouldRetainNonNullArgumentsWithoutTrimming() throws Exception {
        var request =
                json.readValue("{\"name\":\"name\",\"arguments\":\"  inner  space  \"}", BuiltinCommandRequestVO.class);
        assertThat(request.getArguments()).isEqualTo("  inner  space  ");
    }
}
