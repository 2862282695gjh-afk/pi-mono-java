/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Minimal HTTP client for the three CampusMate runtime endpoints used by CampusClaw.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/10]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class MateServiceClient {

    private static final String AGENT_RUNTIME_PATH = "/mate-service/v1/agents/%s/runtime";
    private static final String SKILL_INFO_PATH = "/mate-service/v1/skill/query/%s";
    private static final String SKILL_TOOLS_PATH = "/mate-service/v1/skill/tools/query";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public MateServiceClient(AgentRuntimeProperties properties, ObjectMapper mapper) {
        this(
                properties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .build());
    }

    MateServiceClient(AgentRuntimeProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * Fetches Agent metadata and bound Skill metadata.
     *
     * @param agentId validated Agent identifier
     * @return runtime definition
     * @throws AgentRuntimeException when the HTTP request or response is invalid
     */
    public AgentRuntime getAgentRuntime(String agentId) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(AGENT_RUNTIME_PATH.formatted(agentId)))
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
     * Fetches the complete metadata for a bound Skill reference.
     *
     * @param skillId Skill identifier returned by GetAgentRuntime
     * @return Skill definitions returned by CampusMate
     * @throws AgentRuntimeException when the HTTP request or response is invalid
     */
    public List<SkillInfo> querySkillInfo(String skillId) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(SKILL_INFO_PATH.formatted(skillId)))
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

    /**
     * Queries the locally activatable tools for one or more Skills.
     *
     * @param skillNames selected Skill names
     * @return query response entries
     * @throws AgentRuntimeException when the HTTP request or response is invalid
     */
    public List<SkillTools> querySkillTools(List<String> skillNames) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new SkillToolsQuery(skillNames));
        } catch (IOException e) {
            throw new AgentRuntimeException("Failed to encode querySkillTools request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint(SKILL_TOOLS_PATH))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode root = send(request, "querySkillTools");
        try {
            SkillToolsResponse response = mapper.treeToValue(root, SkillToolsResponse.class);
            if (!properties.successCode().equals(response.resCode())) {
                throw new AgentRuntimeException("querySkillTools failed with resCode "
                        + response.resCode()
                        + (response.resMsg() == null || response.resMsg().isBlank() ? "" : ": " + response.resMsg()));
            }
            return response.result();
        } catch (IOException e) {
            throw new AgentRuntimeException("Invalid querySkillTools response", e);
        }
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

    private URI endpoint(String path) {
        URI baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new AgentRuntimeException(
                    "campusmate.runtime.base-url is required when a managed Agent is not cached locally");
        }
        return baseUrl.resolve(path);
    }

    /** CampusMate GetAgentRuntime response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRuntime(
            String bindingModels,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<SkillReference> bindingSkills,
            List<BoundTool> bindingTools,
            String description,
            String displayName,
            String id,
            String name,
            String systemPrompt,
            List<String> userCases,
            String version,
            AgentReference bindingAgents) {

        public AgentRuntime {
            bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
            bindingTools = bindingTools == null ? List.of() : List.copyOf(bindingTools);
            userCases = userCases == null ? List.of() : List.copyOf(userCases);
        }
    }

    /** Skill reference embedded in GetAgentRuntime. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillReference(String id, String version) {}

    /** Agent binding metadata returned by GetAgentRuntime. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentReference(String id, String name, String displayName, String version) {}

    /** Complete Skill metadata returned by querySkillInfo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillInfo(
            String name,
            String id,
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

    /** Skill dependency returned by querySkillInfo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DependentSkill(String id, String version, String name, String description) {}

    /** Template or reference file returned by querySkillInfo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillFile(String id, String name, String content, String fileType) {}

    /** Tool metadata and permission embedded in Agent or Skill metadata. */
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

    /** querySkillInfo response envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillInfoResponse(String resCode, String resMsg, List<SkillInfo> result) {
        public SkillInfoResponse {
            result = result == null ? List.of() : List.copyOf(result);
        }
    }

    /** querySkillTools request. */
    public record SkillToolsQuery(List<String> skillNames) {
        public SkillToolsQuery {
            skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
        }
    }

    /** querySkillTools response envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillToolsResponse(String resCode, String resMsg, List<SkillTools> result) {
        public SkillToolsResponse {
            result = result == null ? List.of() : List.copyOf(result);
        }
    }

    /** Tools returned for one selected Skill. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillTools(String skillName, List<ToolReference> toolList) {
        public SkillTools {
            toolList = toolList == null ? List.of() : List.copyOf(toolList);
        }
    }

    /** Tool reference returned by querySkillTools. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolReference(String toolId, String toolVersion, String toolName, String description) {}
}
