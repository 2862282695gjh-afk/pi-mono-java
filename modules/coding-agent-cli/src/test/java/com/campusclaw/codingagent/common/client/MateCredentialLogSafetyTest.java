/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.dto.RequestHeaderInfo;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;

/**
 * Mate 凭据和响应异常的日志脱敏回归测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
class MateCredentialLogSafetyTest {
    private static final String HEADER_SECRET = "synthetic-header-secret";

    private static final String RESPONSE_SECRET = "synthetic-response-secret";

    @Test
    void invalidHeaderValueKeepsDiagnosticStackWithoutLoggingSecret() {
        CapturingAppender appender = attach(MateRestUtil.class);
        RequestHeaderInfo headers = RequestHeaderInfo.builder()
                .authorization(HEADER_SECRET + "\ninvalid")
                .build();

        Throwable failure;
        try {
            failure = catchThrowable(
                    () -> new MateRestUtil().executePostRawRequest("http://127.0.0.1:1", "/execute", headers, "{}"));
        } finally {
            detach(MateRestUtil.class, appender);
        }

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operation=POST")
                .hasMessageContaining(IllegalArgumentException.class.getName())
                .hasMessageNotContaining(HEADER_SECRET)
                .hasNoCause();
        assertSafeDiagnostic(appender.events(), HEADER_SECRET, IllegalStateException.class, "jdk.internal.net.http.");
    }

    @Test
    void malformedMateResponseKeepsDiagnosticStackWithoutLoggingOrReturningBody() {
        CapturingAppender appender = attach(HttpMateToolClient.class);
        HttpMateToolClient client = clientReturning("{" + RESPONSE_SECRET);

        MateToolClient.ToolResult result;
        try {
            result = client.callTool("tool-11111111111111111111111111111111", Map.of(), MateCredentials.empty());
        } finally {
            detach(HttpMateToolClient.class, appender);
        }

        assertThat(result.isError()).isTrue();
        assertThat(result.content())
                .isEqualTo("Mate tool execution request failed")
                .doesNotContain(RESPONSE_SECRET);
        assertSafeDiagnostic(appender.events(), RESPONSE_SECRET, RuntimeException.class, "com.fasterxml.jackson.core.");
    }

    @Test
    void requestHeaderInfoToStringExcludesAllExecutionCredentials() {
        RequestHeaderInfo headers = RequestHeaderInfo.builder()
                .xHwId("synthetic-id-secret")
                .xHwAppKey("synthetic-app-key-secret")
                .authorization("synthetic-authorization-secret")
                .accessToken("synthetic-access-token-secret")
                .build();

        assertThat(headers.toString())
                .doesNotContain(
                        "synthetic-id-secret",
                        "synthetic-app-key-secret",
                        "synthetic-authorization-secret",
                        "synthetic-access-token-secret");
    }

    private static HttpMateToolClient clientReturning(String response) {
        MateRestUtil rest = new MateRestUtil() {
            @Override
            public String executePostRawRequest(
                    String campusMateBaseUrl, String path, RequestHeaderInfo headerInfo, String jsonBody) {
                return response;
            }
        };
        return new HttpMateToolClient(
                "http://127.0.0.1",
                "/agents/%s",
                "/skills/%s",
                "/tools/query",
                "/tools/%s/execute",
                rest,
                new ObjectMapper());
    }

    private static CapturingAppender attach(Class<?> source) {
        CapturingAppender appender = new CapturingAppender(source.getSimpleName() + "-credential-safety");
        appender.start();
        ((Logger) LogManager.getLogger(source)).addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> source, CapturingAppender appender) {
        ((Logger) LogManager.getLogger(source)).removeAppender(appender);
        appender.stop();
    }

    private static void assertSafeDiagnostic(
            List<LogEvent> events, String secret, Class<? extends Throwable> errorType, String originalStackPrefix) {
        assertThat(events).hasSize(1);
        LogEvent event = events.getFirst();
        assertThat(event.getMessage().getFormattedMessage()).doesNotContain(secret);
        assertThat(event.getThrown())
                .isInstanceOf(errorType)
                .hasMessageNotContaining(secret)
                .hasNoCause();
        assertThat(event.getThrown().getStackTrace())
                .extracting(StackTraceElement::getClassName)
                .anyMatch(className -> className.startsWith(originalStackPrefix));
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        private List<LogEvent> events() {
            return List.copyOf(events);
        }
    }
}
