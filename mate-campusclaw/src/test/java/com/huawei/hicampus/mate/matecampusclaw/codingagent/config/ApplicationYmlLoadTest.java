/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 加载真实 {@code application.yml}，验证 Mate 网关占位符不会循环引用且支持外部覆盖。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class ApplicationYmlLoadTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void mateGatewayPlaceholderResolvesWithoutCircularReference() {
        runner.run(context -> {
            String resolved = context.getEnvironment().getProperty("mate.innerGWSerive");
            assertThat(resolved).isEqualTo("");
            assertThat(context.getEnvironment().getProperty("mate.endpoints.agent-info-path-prefix"))
                    .isEqualTo("/mate-service/v1/agents/");
            assertThat(context.getEnvironment().getProperty("mate.endpoints.skill-tools-query-path-prefix"))
                    .isEqualTo("/mate-service/v1/skill/info/query/");
            assertThat(context.getEnvironment().getProperty("mate.endpoints.tool-metadata-query-path"))
                    .isEqualTo("/mate-service/v1/runtime/tools/query");
            assertThat(context.getEnvironment().getProperty("mate.endpoints.tool-execute-path-template"))
                    .isEqualTo("/mate-service/v1/runtime/tools/%s/execute");
            assertThat(context.getEnvironment().getProperty("campusmate.runtime.agent-runtime-path-template"))
                    .isEqualTo("/mate-service/v1/agents/%s/runtime");
            assertThat(context.getEnvironment().getProperty("campusmate.runtime.skill-info-query-path-template"))
                    .isEqualTo("/mate-service/v1/skill/query/%s");
        });
    }

    @Test
    void mateGatewayPlaceholderPicksUpEnvironmentVariable() {
        runner.withSystemProperties("MATE_INNERGWSERIVE=http://mate-gateway:8080")
                .run(context -> assertThat(context.getEnvironment().getProperty("mate.innerGWSerive"))
                        .isEqualTo("http://mate-gateway:8080"));
    }

    @Test
    void outboundEndpointPlaceholdersSupportExternalOverrides() {
        runner.withSystemProperties(
                        "MATE_AGENT_INFO_PATH_PREFIX=/custom/agents/",
                        "MATE_SKILL_TOOLS_QUERY_PATH_PREFIX=/custom/skills/",
                        "MATE_TOOL_METADATA_QUERY_PATH=/custom/tools/query",
                        "MATE_TOOL_EXECUTE_PATH_TEMPLATE=/custom/tools/%s/execute",
                        "CAMPUSMATE_AGENT_RUNTIME_PATH_TEMPLATE=/custom/agents/%s/runtime",
                        "CAMPUSMATE_SKILL_INFO_QUERY_PATH_TEMPLATE=/custom/skills/%s")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("mate.endpoints.agent-info-path-prefix"))
                            .isEqualTo("/custom/agents/");
                    assertThat(context.getEnvironment().getProperty("mate.endpoints.skill-tools-query-path-prefix"))
                            .isEqualTo("/custom/skills/");
                    assertThat(context.getEnvironment().getProperty("mate.endpoints.tool-metadata-query-path"))
                            .isEqualTo("/custom/tools/query");
                    assertThat(context.getEnvironment().getProperty("mate.endpoints.tool-execute-path-template"))
                            .isEqualTo("/custom/tools/%s/execute");
                    assertThat(context.getEnvironment().getProperty("campusmate.runtime.agent-runtime-path-template"))
                            .isEqualTo("/custom/agents/%s/runtime");
                    assertThat(context.getEnvironment()
                                    .getProperty("campusmate.runtime.skill-info-query-path-template"))
                            .isEqualTo("/custom/skills/%s");
                });
    }
}
