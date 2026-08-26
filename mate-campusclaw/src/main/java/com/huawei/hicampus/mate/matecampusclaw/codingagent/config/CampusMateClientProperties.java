/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 定义 CampusMate 客户端共享的服务地址与出站 Endpoint 目录。
 *
 * @param baseUrl CampusMate 服务的唯一基础 URL
 * @param endpoints 出站 Endpoint 目录
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusmate")
public record CampusMateClientProperties(URI baseUrl, Endpoints endpoints) {

    /** 配置绑定完成后校验并规范化共享地址与 Endpoint。 */
    @ConstructorBinding
    public CampusMateClientProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        if (endpoints == null) {
            throw new IllegalArgumentException("campusmate.endpoints is required");
        }
        validateUniqueOperations(endpoints);
    }

    /**
     * 将已校验的服务内路径拼接为完整 Endpoint。
     *
     * @param path 已展开的 CampusMate 服务内路径
     * @return 完整 Endpoint
     */
    public URI endpoint(String path) {
        String validated = validateEndpointPath("resolved", path, false);
        return URI.create(baseUrl + validated);
    }

    private static URI normalizeBaseUrl(URI baseUrl) {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("campusmate.base-url must be an absolute HTTP(S) URI with a host");
        }
        String scheme = baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("campusmate.base-url only supports HTTP(S)");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("campusmate.base-url cannot contain user-info, query, or fragment");
        }
        String path = baseUrl.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("campusmate.base-url cannot contain a service path");
        }
        String normalized = baseUrl.toString();
        return URI.create(normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized);
    }

    private static String validateEndpointPath(String name, String path, boolean template) {
        if (path == null || path.isBlank() || !path.startsWith("/mate-service/")) {
            throw new IllegalArgumentException("campusmate.endpoints." + name + " must start with /mate-service/");
        }
        int placeholderCount = countPlaceholders(path);
        int expectedCount = template ? 1 : 0;
        if (placeholderCount != expectedCount) {
            throw new IllegalArgumentException(
                    "campusmate.endpoints." + name + " must contain " + expectedCount + " %s placeholder");
        }
        URI parsed = URI.create(path.replace("%s", "resource-id"));
        if (parsed.isAbsolute()
                || parsed.getAuthority() != null
                || parsed.getQuery() != null
                || parsed.getFragment() != null) {
            throw new IllegalArgumentException("campusmate.endpoints." + name + " must be a service-local path");
        }
        if (containsParentSegment(parsed.getPath())) {
            throw new IllegalArgumentException("campusmate.endpoints." + name + " cannot contain .. segments");
        }
        return path;
    }

    private static int countPlaceholders(String path) {
        int count = 0;
        int index = path.indexOf("%s");
        while (index >= 0) {
            count++;
            index = path.indexOf("%s", index + 2);
        }
        return count;
    }

    private static boolean containsParentSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static void validateUniqueOperations(Endpoints endpoints) {
        List<String> operations = List.of(
                "POST " + endpoints.modelChatPath(),
                "GET " + endpoints.agentInfoPathTemplate(),
                "GET " + endpoints.agentRuntimePathTemplate(),
                "GET " + endpoints.skillInfoPathTemplate(),
                "POST " + endpoints.toolMetadataQueryPath(),
                "POST " + endpoints.toolExecutePathTemplate());
        Set<String> unique = new HashSet<>(operations);
        if (unique.size() != operations.size()) {
            throw new IllegalArgumentException("campusmate.endpoints contains duplicate HTTP operations");
        }
    }

    /**
     * 定义 CampusClaw 当前消费的六个 CampusMate HTTP operation path。
     *
     * @param modelChatPath 模型 Chat 路径
     * @param agentInfoPathTemplate Agent 元数据路径模板
     * @param agentRuntimePathTemplate Agent Runtime 路径模板
     * @param skillInfoPathTemplate Skill 元数据共享路径模板
     * @param toolMetadataQueryPath Tool 元数据批量查询路径
     * @param toolExecutePathTemplate Tool 执行路径模板
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/26]
     * @since [br_eCampusCore 26.0.0]
     */
    public record Endpoints(
            String modelChatPath,
            String agentInfoPathTemplate,
            String agentRuntimePathTemplate,
            String skillInfoPathTemplate,
            String toolMetadataQueryPath,
            String toolExecutePathTemplate) {

        /** 配置绑定完成后校验每个 operation path 与模板占位符。 */
        public Endpoints {
            modelChatPath = validateEndpointPath("model-chat-path", modelChatPath, false);
            agentInfoPathTemplate = validateEndpointPath("agent-info-path-template", agentInfoPathTemplate, true);
            agentRuntimePathTemplate =
                    validateEndpointPath("agent-runtime-path-template", agentRuntimePathTemplate, true);
            skillInfoPathTemplate = validateEndpointPath("skill-info-path-template", skillInfoPathTemplate, true);
            toolMetadataQueryPath = validateEndpointPath("tool-metadata-query-path", toolMetadataQueryPath, false);
            toolExecutePathTemplate = validateEndpointPath("tool-execute-path-template", toolExecutePathTemplate, true);
        }
    }
}
