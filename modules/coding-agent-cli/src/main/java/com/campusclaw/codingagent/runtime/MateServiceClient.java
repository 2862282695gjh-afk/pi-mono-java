/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Minimal HTTP client for the CampusMate Agent and Skill runtime endpoints.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class MateServiceClient {

    private static final String AGENT_RUNTIME_PATH = "/mate-service/v1/agents/%s/runtime";
    private static final String SKILL_INFO_PATH = "/mate-service/v1/skill/query/%s";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
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

        // Concatenate instead of URI.resolve: the API paths are absolute-path
        // segments (/mate-service/...) and resolve() would drop any base path
        // such as http://host:port/mate-service, silently hitting the wrong URL.
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    /** CampusMate GetAgentRuntime response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRuntime(
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> bindingModels,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<SkillReference> bindingSkills,
            List<BoundTool> bindingTools,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> description,
            String displayName,
            String id,
            String name,
            String systemPrompt,
            List<String> userCases,
            String version,
            AgentReference bindingAgents) {

        public AgentRuntime {
            bindingModels = bindingModels == null ? List.of() : List.copyOf(bindingModels);
            bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
            bindingTools = bindingTools == null ? List.of() : List.copyOf(bindingTools);
            description = description == null ? List.of() : List.copyOf(description);
            userCases = userCases == null ? List.of() : List.copyOf(userCases);
        }

        /**
         * Returns the first non-blank bound model, used as the session default.
         *
         * @return default model name, empty when the Agent binds no model
         */
        public Optional<String> defaultModel() {
            return bindingModels.stream()
                    .filter(model -> model != null && !model.isBlank())
                    .findFirst();
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
            // Leading-space alias is deliberate, not a typo: the production CampusMate
            // querySkillInfo payload serializes this key as " version".
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
}
