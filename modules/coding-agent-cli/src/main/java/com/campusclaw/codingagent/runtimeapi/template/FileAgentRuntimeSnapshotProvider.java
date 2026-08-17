/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.template;

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

import org.springframework.http.HttpStatus;

/**
 * 从受控本地目录读取 Agent 当前激活 revision 的默认快照解析器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class FileAgentRuntimeSnapshotProvider implements AgentRuntimeSnapshotProvider {
    private final RuntimeTemplateProperties properties;

    private final ObjectMapper objectMapper;

    public FileAgentRuntimeSnapshotProvider(RuntimeTemplateProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRuntimeSnapshotDTO resolveCurrent(String agentId) {
        Path agentDirectory = safeAgentDirectory(agentId);
        if (!Files.isDirectory(agentDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.AGENT_NOT_FOUND);
        }
        try {
            String revision = requiredText(
                    objectMapper.readTree(agentDirectory.resolve("current.json").toFile()), "bundleRevision");
            return readRevision(agentId, agentDirectory, revision);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
    }

    @Override
    public AgentRuntimeSnapshotDTO resolveRevision(String agentId, String bundleRevision) {
        Path agentDirectory = safeAgentDirectory(agentId);
        if (!Files.isDirectory(agentDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.AGENT_NOT_FOUND);
        }
        try {
            return readRevision(agentId, agentDirectory, bundleRevision);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_NOT_AVAILABLE, error);
        }
    }

    private AgentRuntimeSnapshotDTO readRevision(String agentId, Path agentDirectory, String revision)
            throws IOException {
        Path runtimeDirectory = safeRevisionDirectory(agentDirectory, revision);
        JsonNode settings = objectMapper.readTree(
                runtimeDirectory.resolve(".campusagent/settings.json").toFile());
        String defaultModel = requiredText(settings, "defaultModel");
        List<String> enabledModels = readModels(settings, defaultModel);
        return new AgentRuntimeSnapshotDTO(agentId, revision, defaultModel, enabledModels, runtimeDirectory);
    }

    private Path safeAgentDirectory(String agentId) {
        Path root = properties.getRoot().toAbsolutePath().normalize();
        Path candidate = root.resolve(agentId).normalize();
        if (!candidate.startsWith(root)) {
            throw new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.AGENT_NOT_FOUND);
        }
        return candidate;
    }

    private static Path safeRevisionDirectory(Path agentDirectory, String revision) {
        if (revision.contains("/") || revision.contains("\\") || revision.contains("..")) {
            throw new IllegalArgumentException("invalid bundle revision");
        }
        Path revisions = agentDirectory.resolve("revisions").normalize();
        Path candidate = revisions.resolve(revision).normalize();
        if (!candidate.startsWith(revisions) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("revision is unavailable");
        }
        return candidate;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return value;
    }

    private static List<String> readModels(JsonNode settings, String defaultModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        JsonNode enabled = settings.path("enabledModels");
        if (enabled.isArray()) {
            enabled.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    models.add(item.asText());
                }
            });
        }
        if (models.isEmpty() || !models.contains(defaultModel)) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return List.copyOf(models);
    }
}
