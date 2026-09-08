/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.service.command.CommandExecutionService;
import com.campusclaw.codingagent.runtimeapi.service.command.skill.SkillCommandExecutionService;
import com.campusclaw.codingagent.test.Log4j2TestAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 使用实际 MVC 绑定和校验验证共享命令错误 JSON、状态及敏感输入日志隔离。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCommandErrorTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private static final String ROUTE = "/campusclaw-service/v1/sessions/{sessionId}/command";

    private final ObjectMapper mapper = new ObjectMapper();

    private final CommandExecutionService builtins = mock(CommandExecutionService.class);

    private final SkillCommandExecutionService skills = mock(SkillCommandExecutionService.class);

    private final RuntimeSseDispatcher dispatcher = new RuntimeSseDispatcher();

    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    private final Log4j2TestAppender capture = new Log4j2TestAppender("command-errors");

    private final Logger logger = (Logger) LogManager.getLogger(RuntimeExceptionHandler.class);

    private Level originalLevel;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        new RuntimeCommandController(builtins, skills, new StandaloneResultBeanAdapter(), dispatcher))
                .setControllerAdvice(
                        new RuntimeExceptionHandler(new RuntimeMessageSourceConfiguration().messageSource()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
        originalLevel = logger.getLevel();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void closeResources() {
        logger.removeAppender(capture);
        logger.setLevel(originalLevel);
        capture.stop();
        validator.close();
        dispatcher.close();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void testInvalidBodyIsJsonBeforeAnyExecution(String body) throws Exception {
        assertError(submit(SESSION_ID, body), 400, "INVALID_COMMAND_REQUEST");
        verifyNoInteractions(builtins, skills);
    }

    private static Stream<String> invalidBodies() {
        return Stream.of(
                "",
                "null",
                "[]",
                "1",
                "{}",
                "{",
                "{\"name\":null}",
                "{\"name\":1}",
                "{\"name\":\"/help\"}",
                "{\"name\":\"skill:PDF\"}",
                "{\"name\":\"other:pdf\"}",
                "{\"name\":\"help\",\"arguments\":false}",
                "{\"name\":\"help\",\"fileIds\":null}",
                "{\"name\":\"help\",\"unknown\":\"BODY_CANARY\"}",
                "{\"name\":\"skill:pdf\",\"fileIds\":\"FILE_CANARY\"}",
                "{\"name\":\"skill:pdf\",\"fileIds\":[null]}",
                "{\"name\":\"skill:pdf\",\"fileIds\":[1]}",
                "{\"name\":\"skill:pdf\",\"fileIds\":[\" \" ]}",
                "{\"name\":\"" + "a".repeat(65) + "\"}",
                "{\"name\":\"skill:" + "a".repeat(65) + "\"}",
                "{\"name\":\"skill:pdf\",\"fileIds\":[" + "\"a\",".repeat(32) + "\"b\"]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "SESSION-0123456789abcdef0123456789abcdef"})
    void testInvalidPathPrecedesValidlyBoundButInvalidBody(String sessionId) throws Exception {
        var result = submit(sessionId, "{\"name\":\"/help\"}");
        assertError(result, 400, "INVALID_SESSION_ID");
        assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        verifyNoInteractions(builtins, skills);
    }

    @ParameterizedTest
    @ValueSource(strings = {"help", "skill:pdf"})
    void testMethodValidationDoesNotLogRejectedArgumentsOrCredentials(String name) throws Exception {
        String body = mapper.writeValueAsString(Map.of("name", name, "arguments", "BODY_CANARY" + "x".repeat(262144)));
        var result = submit(SESSION_ID, body);
        assertError(result, 400, "INVALID_COMMAND_REQUEST");
        assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        assertSanitizedLog();
        verifyNoInteractions(builtins, skills);
    }

    @Test
    void testBindingFailureDoesNotLogBodyOrFileInput() throws Exception {
        assertError(
                submit(SESSION_ID, "{\"name\":\"help\",\"fileIds\":[\"FILE_CANARY\"]}"),
                400,
                "INVALID_COMMAND_REQUEST");
        assertSanitizedLog();
        verifyNoInteractions(builtins, skills);
    }

    @ParameterizedTest
    @CsvSource({
        "INVALID_COMMAND_REQUEST,400",
        "SESSION_NOT_FOUND,404",
        "COMMAND_NOT_FOUND,404",
        "SESSION_BUSY,409",
        "AGENT_NOT_AVAILABLE,422",
        "MODEL_NOT_AVAILABLE,422",
        "THINKING_NOT_SUPPORTED,422",
        "COMMAND_EXECUTION_FAILED,500",
        "SESSION_NAME_UPDATE_FAILED,500",
        "MANAGER_UNAVAILABLE,503",
        "RUNTIME_CAPACITY_EXCEEDED,503"
    })
    void testApplicationErrorsStayJsonForSseOnlyAccept(String code, int expectedStatus) throws Exception {
        var error = new RuntimeApiException(RuntimeErrorCode.valueOf(code));
        when(builtins.executeBuiltinAndAwait(any(), any(), any(), any())).thenThrow(error);
        when(skills.executeRequest(any(), any(), any(), any())).thenThrow(error);
        for (String name : List.of("help", "skill:pdf")) {
            var result = submit(SESSION_ID, mapper.writeValueAsString(Map.of("name", name)));
            assertError(result, expectedStatus, code);
            assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(expectedStatus == 503 ? "3" : null);
        }
    }

    @Test
    void testUnexpectedFailureDoesNotLeakCauseOrReturnInternalCode() throws Exception {
        when(builtins.executeBuiltinAndAwait(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("BODY_CANARY CREDENTIAL_CANARY"));
        assertError(submit(SESSION_ID, "{\"name\":\"help\"}"), 500, "COMMAND_EXECUTION_FAILED");
        assertSanitizedLog();
        verify(builtins).executeBuiltinAndAwait(any(), any(), any(), any());
    }

    @Test
    void testErrorReplacesUncommittedSseContentTypeWithJson() throws Exception {
        when(skills.executeRequest(any(), any(), any(), any())).thenAnswer(invocation -> {
            var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            attributes.getResponse().setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_BUSY);
        });
        assertError(submit(SESSION_ID, "{\"name\":\"skill:pdf\"}"), 409, "SESSION_BUSY");
        verify(skills).executeRequest(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"If-Match", "Idempotency-Key"})
    void testForbiddenHeaderPresenceRejectsBothKindsIncludingEmptyValue(String header) throws Exception {
        for (String name : List.of("help", "skill:pdf")) {
            for (String value : List.of("", "opaque")) {
                var result = mvc.perform(post(ROUTE, SESSION_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                                .header(header, value)
                                .content(mapper.writeValueAsString(Map.of("name", name))))
                        .andReturn();
                assertError(result, 400, "INVALID_COMMAND_REQUEST");
            }
        }
        verifyNoInteractions(builtins, skills);
    }

    private MvcResult submit(String sessionId, String body) throws Exception {
        return mvc.perform(post(ROUTE, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer CREDENTIAL_CANARY")
                        .content(body))
                .andReturn();
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo("zh-CN");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getRequest().isAsyncStarted()).isFalse();
        var body = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("resCode").asText()).isEqualTo(code);
        assertThat(body.path("resMsg").asText()).isNotBlank();
        assertThat(body.toString()).doesNotContain("BODY_CANARY", "FILE_CANARY", "CREDENTIAL_CANARY");
    }

    private void assertSanitizedLog() {
        assertThat(capture.events()).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getLevel()).isIn(Level.WARN, Level.ERROR);
            assertThat(event.getThrown()).isNull();
            assertThat(event.getMessage().getFormattedMessage())
                    .contains("CampusClaw failure")
                    .doesNotContain("BODY_CANARY", "FILE_CANARY", "CREDENTIAL_CANARY");
        });
    }
}
