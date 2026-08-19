/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从受控本地根目录解析 Agent 当前只读配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class FileAgentDirectoryResolver implements AgentDirectoryResolver {
    private final RuntimeAgentDirectoryProperties properties;

    private final ObjectMapper objectMapper;

    public FileAgentDirectoryResolver(RuntimeAgentDirectoryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentDirectorySnapshotDTO resolve(String agentId) {
        Path agentDirectory = safeAgentDirectory(agentId);
        try {
            Path runtimeDirectory = requiredRuntimeDirectory(agentDirectory);
            Path settingsFile = requiredManagedFile(runtimeDirectory, Path.of("settings.json"));
            JsonNode settings = objectMapper.readTree(settingsFile.toFile());
            String defaultModel = requiredText(settings, "defaultModel");
            List<String> enabledModels = readModels(settings, defaultModel);
            return new AgentDirectorySnapshotDTO(agentId, defaultModel, enabledModels, runtimeDirectory);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
    }

    private static Path requiredRuntimeDirectory(Path agentDirectory) {
        Path candidate = agentDirectory.resolve(RuntimeAgentDirectoryProperties.MANAGED_DIRECTORY_NAME);
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        try {
            Path realDirectory = candidate.toRealPath();
            if (!realDirectory.startsWith(agentDirectory)) {
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
            }
            return realDirectory;
        } catch (IOException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
    }

    private static Path requiredManagedFile(Path agentDirectory, Path relativePath) {
        Path candidate = agentDirectory.resolve(relativePath).normalize();
        if (!candidate.startsWith(agentDirectory) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        try {
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(agentDirectory)) {
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
            }
            return realFile;
        } catch (IOException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
    }

    private Path safeAgentDirectory(String agentId) {
        Path root = properties.getRoot().toAbsolutePath().normalize();
        Path candidate = root.resolve(agentId).normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_FOUND);
        }
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_FOUND);
            }
            return realCandidate;
        } catch (IOException error) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_FOUND, error);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return value;
    }

    private static List<String> readModels(JsonNode settings, String defaultModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        JsonNode enabled = settings.path("enabledModels");
        if (enabled.isArray()) {
            enabled.forEach(item -> addModel(models, item));
        }
        if (models.isEmpty() || !models.contains(defaultModel)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return List.copyOf(models);
    }

    private static void addModel(LinkedHashSet<String> models, JsonNode item) {
        if (!item.isTextual() || item.asText().isBlank() || !models.add(item.asText())) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
    }
}
