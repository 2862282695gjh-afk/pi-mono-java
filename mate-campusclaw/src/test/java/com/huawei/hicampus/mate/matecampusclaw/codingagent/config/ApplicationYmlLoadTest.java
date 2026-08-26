/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 加载真实 {@code application.yml}，验证 CampusMate 统一配置及环境变量覆盖。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class ApplicationYmlLoadTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withSystemProperties("CAMPUSMATE_BASE_URL=http://campusmate-service:8080");

    @Test
    void targetConfigurationUsesOneOriginAndSixEndpoints() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("campusmate.base-url"))
                    .isEqualTo("http://campusmate-service:8080");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.model-chat-path"))
                    .isEqualTo("/mate-service/v1/LLM/chat");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.agent-info-path-template"))
                    .isEqualTo("/mate-service/v1/agents/%s");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.agent-runtime-path-template"))
                    .isEqualTo("/mate-service/v1/agents/%s/runtime");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.skill-info-path-template"))
                    .isEqualTo("/mate-service/v1/skill/query/%s");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.tool-metadata-query-path"))
                    .isEqualTo("/mate-service/v1/runtime/tools/query");
            assertThat(context.getEnvironment().getProperty("campusmate.endpoints.tool-execute-path-template"))
                    .isEqualTo("/mate-service/v1/runtime/tools/%s/execute");
            assertThat(context.getEnvironment().getProperty("campusmate.model.api"))
                    .isEqualTo("openai-completions");
            assertThat(context.getEnvironment().getProperty("campusmate.tool.enabled"))
                    .isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("mate.innerGWSerive"))
                    .isNull();
            assertThat(context.getEnvironment().getProperty("campusmate.model-manager.base-url"))
                    .isNull();
        });
    }

    @Test
    void sharedBaseUrlPicksUpEnvironmentVariable() {
        runner.withSystemProperties("CAMPUSMATE_BASE_URL=https://campusmate.example.com:9443")
                .run(context -> assertThat(context.getEnvironment().getProperty("campusmate.base-url"))
                        .isEqualTo("https://campusmate.example.com:9443"));
    }

    @Test
    void outboundEndpointPlaceholdersSupportExternalOverrides() {
        runner.withSystemProperties(
                        "CAMPUSMATE_MODEL_CHAT_PATH=/mate-service/custom/chat",
                        "CAMPUSMATE_AGENT_INFO_PATH_TEMPLATE=/mate-service/custom/agents/%s",
                        "CAMPUSMATE_AGENT_RUNTIME_PATH_TEMPLATE=/mate-service/custom/agents/%s/runtime",
                        "CAMPUSMATE_SKILL_INFO_PATH_TEMPLATE=/mate-service/custom/skills/%s",
                        "CAMPUSMATE_TOOL_METADATA_QUERY_PATH=/mate-service/custom/tools/query",
                        "CAMPUSMATE_TOOL_EXECUTE_PATH_TEMPLATE=/mate-service/custom/tools/%s/execute")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.model-chat-path"))
                            .isEqualTo("/mate-service/custom/chat");
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.agent-info-path-template"))
                            .isEqualTo("/mate-service/custom/agents/%s");
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.agent-runtime-path-template"))
                            .isEqualTo("/mate-service/custom/agents/%s/runtime");
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.skill-info-path-template"))
                            .isEqualTo("/mate-service/custom/skills/%s");
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.tool-metadata-query-path"))
                            .isEqualTo("/mate-service/custom/tools/query");
                    assertThat(context.getEnvironment().getProperty("campusmate.endpoints.tool-execute-path-template"))
                            .isEqualTo("/mate-service/custom/tools/%s/execute");
                });
    }
}
