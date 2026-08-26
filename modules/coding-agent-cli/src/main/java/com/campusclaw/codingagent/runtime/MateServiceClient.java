/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.campusclaw.codingagent.config.CampusMateClientProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * CampusMate Agent 与 Skill 运行时接口的最小 HTTP 客户端。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class MateServiceClient {

    private final AgentRuntimeProperties properties;
    private final CampusMateClientProperties campusMateProperties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public MateServiceClient(
            AgentRuntimeProperties properties, CampusMateClientProperties campusMateProperties, ObjectMapper mapper) {
        this(
                properties,
                campusMateProperties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .build());
    }

    MateServiceClient(
            AgentRuntimeProperties properties,
            CampusMateClientProperties campusMateProperties,
            ObjectMapper mapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.campusMateProperties = campusMateProperties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * 获取 Agent 元数据和绑定 Skill 坐标。
     *
     * @param agentId 已校验的 Agent 标识
     * @return 运行时定义
     * @throws IllegalArgumentException Agent 标识不符合类型化 UUID 格式时抛出
     * @throws AgentRuntimeException HTTP 请求或响应无效时抛出
     */
    public AgentRuntime getAgentRuntime(String agentId) {
        requireIdentifier(agentId, ResourceIdentifierPatterns.AGENT_ID_PATTERN, "agentId");
        String path = expandPathTemplate(campusMateProperties.endpoints().agentRuntimePathTemplate(), agentId);
        HttpRequest request = HttpRequest.newBuilder(campusMateProperties.endpoint(path))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode root = send(request, "GetAgentRuntime");
        validateBusinessSuccess(root, "GetAgentRuntime");
        JsonNode payload = root.hasNonNull("result") ? root.get("result") : root;
        try {
            return mapper.treeToValue(payload, AgentRuntime.class);
        } catch (IOException e) {
            throw new AgentRuntimeException("Invalid GetAgentRuntime response", e);
        }
    }

    /**
     * 获取已绑定 Skill 的完整元数据。
     *
     * @param skillId GetAgentRuntime 返回的 Skill 标识
     * @return CampusMate 返回的 Skill 定义
     * @throws IllegalArgumentException Skill 标识不符合类型化 UUID 格式时抛出
     * @throws AgentRuntimeException HTTP 请求或响应无效时抛出
     */
    public List<SkillInfo> querySkillInfo(String skillId) {
        requireIdentifier(skillId, ResourceIdentifierPatterns.SKILL_ID_PATTERN, "skillId");
        String path = expandPathTemplate(campusMateProperties.endpoints().skillInfoPathTemplate(), skillId);
        HttpRequest request = HttpRequest.newBuilder(campusMateProperties.endpoint(path))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode root = send(request, "querySkillInfo");
        try {
            SkillInfoResponse response = mapper.treeToValue(root, SkillInfoResponse.class);
            if (!properties.successCode().equals(response.resCode())) {
                throw new AgentRuntimeException("querySkillInfo failed with resCode "
                        + response.resCode()
                        + (response.resMsg() == null || response.resMsg().isBlank() ? "" : ": " + response.resMsg()));
            }
            return response.result();
        } catch (IOException e) {
            throw new AgentRuntimeException("Invalid querySkillInfo response", e);
        }
    }

    private static void requireIdentifier(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private static String expandPathTemplate(String template, String resourceId) {
        return template.replace("%s", resourceId);
    }

    private JsonNode send(HttpRequest request, String operation) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRuntimeException(operation + " interrupted", e);
        } catch (IOException e) {
            throw new AgentRuntimeException(operation + " request failed", e);
        }
        byte[] responseBytes;
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentRuntimeException(operation + " returned HTTP " + response.statusCode());
            }
            int maxResponseBytes = properties.maxResponseBytes();
            long contentLength =
                    response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxResponseBytes) {
                throw responseTooLarge(operation);
            }
            responseBytes = body.readNBytes(maxResponseBytes + 1);
            if (responseBytes.length > maxResponseBytes) {
                throw responseTooLarge(operation);
            }
        } catch (IOException e) {
            throw new AgentRuntimeException(operation + " response body could not be read", e);
        }
        try {
            return mapper.readTree(responseBytes);
        } catch (IOException e) {
            throw new AgentRuntimeException(operation + " returned invalid JSON", e);
        }
    }

    private AgentRuntimeException responseTooLarge(String operation) {
        return new AgentRuntimeException(operation + " response exceeds campusmate.runtime.max-response-bytes ("
                + properties.maxResponseBytes() + ")");
    }

    private void validateBusinessSuccess(JsonNode root, String operation) {
        if (!root.has("resCode")) {
            return;
        }
        String actual = root.path("resCode").asText();
        if (!properties.successCode().equals(actual)) {
            String message = root.path("resMsg").asText("");
            throw new AgentRuntimeException(
                    operation + " failed with resCode " + actual + (message.isBlank() ? "" : ": " + message));
        }
    }

    /** CampusMate GetAgentRuntime 响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRuntime(
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> bindingModels,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<SkillReference> bindingSkills,
            List<BoundTool> bindingTools,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<AgentReference> bindingAgents,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> description,
            String displayName,
            Boolean enabled,
            String id,
            String name,
            String systemPrompt,
            List<String> userCases,
            String version) {

        public AgentRuntime {
            // 旧快照缺省 enabled 字段（反序列化为 null）视为启用；归一化保证 enabled() 永不返回 null
            enabled = enabled == null ? Boolean.TRUE : enabled;
            bindingModels = bindingModels == null ? List.of() : List.copyOf(bindingModels);
            bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
            bindingTools = bindingTools == null ? List.of() : List.copyOf(bindingTools);
            bindingAgents = bindingAgents == null ? List.of() : List.copyOf(bindingAgents);
            description = description == null ? List.of() : List.copyOf(description);
            userCases = userCases == null ? List.of() : List.copyOf(userCases);
        }

        /**
         * 返回绑定的第一个非空白模型名，作为会话缺省模型。
         *
         * @return 缺省模型名；Agent 未绑定任何模型时为空
         */
        public Optional<String> defaultModel() {
            return bindingModels.stream()
                    .filter(model -> model != null && !model.isBlank())
                    .findFirst();
        }
    }

    /** GetAgentRuntime 内嵌的 Skill 引用。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillReference(String id, String version) {}

    /**
     * GetAgentRuntime 返回的子 Agent 绑定元数据。携带 {@code description} 使父 Agent
     * 呈现子候选时无需加载子 Agent 的完整运行时。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentReference(
            String id, String name, String displayName, String description, String version, Boolean enabled) {

        public AgentReference {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }

        public AgentReference(String id, String name, String displayName, String description, String version) {
            this(id, name, displayName, description, version, Boolean.TRUE);
        }
    }

    /** querySkillInfo 返回的完整 Skill 元数据。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillInfo(
            String name,
            String id,
            // 前导空格别名用于兼容生产 querySkillInfo 把字段序列化为 " version" 的现状。
            @JsonAlias(" version") String version,
            String description,
            String useCases,
            List<BoundTool> bindingTools,
            List<DependentSkill> bindingSkills,
            List<SkillFile> templates,
            List<SkillFile> references) {

        public SkillInfo {
            bindingTools = bindingTools == null ? List.of() : List.copyOf(bindingTools);
            bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
            templates = templates == null ? List.of() : List.copyOf(templates);
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    /** querySkillInfo 返回的 Skill 依赖。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DependentSkill(String id, String version, String name, String description) {}

    /** querySkillInfo 返回的模板或引用文件。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillFile(String id, String name, String content, String fileType) {}

    /** Agent 或 Skill 元数据中内嵌的工具元数据与权限。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoundTool(
            String description,
            String displayName,
            String id,
            String isConcurrencySafe,
            String name,
            String permission,
            String source,
            String version) {}

    /** querySkillInfo 响应信封。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillInfoResponse(String resCode, String resMsg, List<SkillInfo> result) {
        public SkillInfoResponse {
            result = result == null ? List.of() : List.copyOf(result);
        }
    }
}
