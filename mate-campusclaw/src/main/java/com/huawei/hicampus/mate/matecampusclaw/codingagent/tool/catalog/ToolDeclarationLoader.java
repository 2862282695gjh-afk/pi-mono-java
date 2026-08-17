/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads YAML/JSON declarative tool definitions.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ToolDeclarationLoader {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    public ToolDeclaration load(Path path) {
        try {
            Object loaded = yaml.load(Files.readString(path, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("declaration root must be a map");
            }
            return toDeclaration(root);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read declaration " + path + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("failed to load declaration " + path + ": " + e.getMessage(), e);
        }
    }

    private ToolDeclaration toDeclaration(Map<?, ?> root) {
        String kind = string(root.get("kind"), "kind");
        if (!"Tool".equals(kind)) {
            throw new IllegalArgumentException("kind must be Tool");
        }
        var metadata = map(root.get("metadata"), "metadata");
        var spec = map(root.get("spec"), "spec");
        String name = string(metadata.get("name"), "metadata.name");
        String label = optionalString(metadata.get("label"), name);
        String description = string(spec.get("description"), "spec.description");
        Object rawSchema = spec.get("inputSchema");
        JsonNode inputSchema = objectMapper.valueToTree(rawSchema != null ? rawSchema : Map.of("type", "object"));
        var execution = execution(map(spec.get("execution"), "spec.execution"));
        var merge = merge(spec.get("merge"));
        return new ToolDeclaration(
                name, label, description, inputSchema, execution, merge.strategy(), merge.replaces());
    }

    private ToolDeclaration.Execution execution(Map<?, ?> raw) {
        String type = optionalString(raw.get("type"), "process");
        if (!"process".equals(type)) {
            throw new IllegalArgumentException("spec.execution.type must be process");
        }
        var command = stringList(raw.get("command"), "spec.execution.command");
        int timeoutSeconds = integer(raw.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        var env = stringMap(raw.get("env"));
        return new ToolDeclaration.Execution(type, command, timeoutSeconds, env);
    }

    private Merge merge(Object raw) {
        if (raw == null) {
            return new Merge(ToolMergeStrategy.ADD, null);
        }
        var map = map(raw, "spec.merge");
        String strategy = optionalString(map.get("strategy"), "ADD").toUpperCase(Locale.ROOT);
        return new Merge(ToolMergeStrategy.valueOf(strategy), optionalString(map.get("replaces"), null));
    }

    private Map<?, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(field + " must be a map");
    }

    private List<String> stringList(Object value, String field) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> string(item, field)).toList();
        }
        throw new IllegalArgumentException(field + " must be a list");
    }

    private Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        var map = map(value, "spec.execution.env");
        return map.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> string(entry.getKey(), "env key"), entry -> string(entry.getValue(), "env value")));
    }

    private String string(Object value, String field) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(field + " must be a non-blank string");
    }

    private String optionalString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return string(value, "optional string");
    }

    private int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("integer field must be a number");
    }

    private record Merge(ToolMergeStrategy strategy, String replaces) {}
}
