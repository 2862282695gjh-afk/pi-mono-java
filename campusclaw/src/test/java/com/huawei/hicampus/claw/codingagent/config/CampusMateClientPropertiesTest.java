/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * {@link CampusMateClientProperties} 的共享 origin、路径和 operation 唯一性测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
class CampusMateClientPropertiesTest {

    @Test
    void normalizesTrailingSlashAndBuildsEndpoint() {
        CampusMateClientProperties properties =
                new CampusMateClientProperties(URI.create("https://campusmate.example.com:9443/"), endpoints());

        assertThat(properties.baseUrl()).isEqualTo(URI.create("https://campusmate.example.com:9443"));
        assertThat(properties.endpoint("/mate-service/v1/LLM/chat"))
                .isEqualTo(URI.create("https://campusmate.example.com:9443/mate-service/v1/LLM/chat"));
    }

    @Test
    void rejectsBaseUrlThatContainsServicePath() {
        assertThatThrownBy(() -> new CampusMateClientProperties(
                        URI.create("https://campusmate.example.com/mate-service"), endpoints()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service path");
    }

    @Test
    void rejectsEndpointOutsideMateService() {
        assertThatThrownBy(() -> new CampusMateClientProperties.Endpoints(
                        "/other-service/v1/LLM/chat",
                        "/mate-service/v1/agents/%s",
                        "/mate-service/v1/agents/%s/runtime",
                        "/mate-service/v1/skill/query/%s",
                        "/mate-service/v1/runtime/tools/query",
                        "/mate-service/v1/runtime/tools/%s/execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-chat-path");
    }

    @Test
    void rejectsTemplateWithoutExactlyOnePlaceholder() {
        assertThatThrownBy(() -> new CampusMateClientProperties.Endpoints(
                        "/mate-service/v1/LLM/chat",
                        "/mate-service/v1/agents",
                        "/mate-service/v1/agents/%s/runtime",
                        "/mate-service/v1/skill/query/%s",
                        "/mate-service/v1/runtime/tools/query",
                        "/mate-service/v1/runtime/tools/%s/execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent-info-path-template");
    }

    @Test
    void rejectsCurrentDirectorySegmentsBeforeOperationComparison() {
        assertThatThrownBy(() -> new CampusMateClientProperties.Endpoints(
                        "/mate-service/v1/LLM/chat",
                        "/mate-service/v1/agents/./%s",
                        "/mate-service/v1/agents/%s/runtime",
                        "/mate-service/v1/skill/query/%s",
                        "/mate-service/v1/runtime/tools/query",
                        "/mate-service/v1/runtime/tools/%s/execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot contain . or .. segments");

        assertThatThrownBy(() -> new CampusMateClientProperties.Endpoints(
                        "/mate-service/v1/LLM/chat",
                        "/mate-service/v1/agents/%2e/%s",
                        "/mate-service/v1/agents/%s/runtime",
                        "/mate-service/v1/skill/query/%s",
                        "/mate-service/v1/runtime/tools/query",
                        "/mate-service/v1/runtime/tools/%s/execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot contain . or .. segments");
    }

    @Test
    void rejectsDuplicateMethodAndPathOperation() {
        CampusMateClientProperties.Endpoints duplicate = new CampusMateClientProperties.Endpoints(
                "/mate-service/v1/LLM/chat",
                "/mate-service/v1/agents/%s",
                "/mate-service/v1/agents/%s/runtime",
                "/mate-service/v1/skill/query/%s",
                "/mate-service/v1/LLM/chat",
                "/mate-service/v1/runtime/tools/%s/execute");

        assertThatThrownBy(
                        () -> new CampusMateClientProperties(URI.create("https://campusmate.example.com"), duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate HTTP operations");
    }

    private static CampusMateClientProperties.Endpoints endpoints() {
        return new CampusMateClientProperties.Endpoints(
                "/mate-service/v1/LLM/chat",
                "/mate-service/v1/agents/%s",
                "/mate-service/v1/agents/%s/runtime",
                "/mate-service/v1/skill/query/%s",
                "/mate-service/v1/runtime/tools/query",
                "/mate-service/v1/runtime/tools/%s/execute");
    }
}
