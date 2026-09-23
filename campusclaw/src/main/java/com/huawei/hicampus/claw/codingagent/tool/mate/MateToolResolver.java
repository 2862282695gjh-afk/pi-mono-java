/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在单个 Session 内按可信 Agent 与 Skill 工具关联解析定义，关联不代替调用授权。
 */
final class MateToolResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MateToolResolver.class);

    private final MateToolClient client;

    private final String agentId;

    private final PreparedAgentRuntime runtime;

    private volatile Map<String, TrustedBinding> agentBindings;

    private volatile Map<String, TrustedBinding> skillBindings;

    private volatile Map<String, TrustedBinding> disclosableSkillBindings;

    private final Map<String, ResolvedTool> skillCache = new ConcurrentHashMap<>();

    private final Map<String, MateToolMeta> metadataById = new ConcurrentHashMap<>();

    private volatile List<ResolvedTool> agentTools;

    MateToolResolver(MateToolClient client, PreparedAgentRuntime runtime) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        PreparedAgentRuntime snapshot = java.util.Objects.requireNonNull(runtime, "runtime");
        this.agentId = snapshot.agentId();
        this.runtime = snapshot;
    }

    List<ResolvedTool> resolveAgentTools() {
        ensureInitialized();
        List<ResolvedTool> current = agentTools;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (agentTools == null) {
                agentTools = loadAgentTools();
            }
            return agentTools;
        }
    }

    ResolvedTool resolveAgent(String name) {
        return resolveAgentTools().stream()
                .filter(tool -> tool.binding().name().equals(name))
                .findFirst()
                .orElseThrow(() ->
                        failure(name, ResolutionFailure.NAME_UNAVAILABLE, "not directly bound to the current Agent"));
    }

    ResolvedTool resolveSkill(String name) {
        ensureInitialized();
        TrustedBinding binding = skillBindings.get(name);
        if (binding == null) {
            throw failure(name, ResolutionFailure.NAME_UNAVAILABLE, "not bound through a directly bound Skill");
        }
        return skillCache.computeIfAbsent(name, ignored -> loadSkillTool(binding));
    }

    ResolvedTool resolveDisclosableSkill(String name) {
        ensureInitialized();
        if (!disclosableSkillBindings.containsKey(name)) {
            throw failure(
                    name,
                    ResolutionFailure.NAME_UNAVAILABLE,
                    "not available through a model-visible directly bound Skill");
        }
        return resolveSkill(name);
    }

    private List<ResolvedTool> loadAgentTools() {
        if (agentBindings.isEmpty()) {
            return List.of();
        }
        List<MateToolMeta> metadata;
        try {
            metadata = client.listAgentTools(agentId);
        } catch (RuntimeException error) {
            throw discoveryFailure("mate.tool.listAgentTools", "agentId", agentId, "Agent tools", error);
        }
        List<ResolvedTool> resolved = new ArrayList<>();
        for (TrustedBinding binding : agentBindings.values()) {
            ResolvedTool observed = resolveMetadata(binding, metadata, isDisclosable(binding.name()));
            MateToolMeta canonical = metadataById.putIfAbsent(binding.id(), observed.metadata());
            resolved.add(
                    resolved(binding, canonical == null ? observed.metadata() : canonical, observed.disclosable()));
        }
        return List.copyOf(resolved);
    }

    private void ensureInitialized() {
        if (agentBindings != null) {
            return;
        }
        synchronized (this) {
            if (agentBindings != null) {
                return;
            }
            Map<String, TrustedBinding> agents =
                    indexAgentBindings(runtime.metadata().bindingTools());
            Map<String, TrustedBinding> skills = indexSkillBindings(runtime.skills());
            Map<String, TrustedBinding> disclosableSkills = indexDisclosableSkillBindings(runtime.skills());
            validateCrossScopeNames(agents, skills);
            disclosableSkillBindings = disclosableSkills;
            skillBindings = skills;
            agentBindings = agents;
        }
    }

    private ResolvedTool loadSkillTool(TrustedBinding binding) {
        MateToolMeta cached = metadataById.get(binding.id());
        if (cached != null) {
            return resolved(binding, cached, isDisclosable(binding.name()));
        }
        for (String skillId : binding.skillIds()) {
            List<MateToolMeta> metadata;
            try {
                metadata = client.listSkillTools(skillId);
            } catch (RuntimeException error) {
                throw discoveryFailure("mate.tool.listSkillTools", "skillId", skillId, binding.name(), error);
            }
            MateToolMeta match = findMetadata(binding, metadata);
            if (match != null) {
                MateToolMeta canonical = metadataById.putIfAbsent(binding.id(), match);
                return resolved(binding, canonical == null ? match : canonical, isDisclosable(binding.name()));
            }
        }
        throw failure(
                binding.name(),
                ResolutionFailure.METADATA_UNAVAILABLE,
                "trusted Skill binding metadata is unavailable");
    }

    private static ResolvedTool resolveMetadata(
            TrustedBinding binding, List<MateToolMeta> metadata, boolean disclosable) {
        MateToolMeta match = findMetadata(binding, metadata);
        if (match == null) {
            throw failure(
                    binding.name(),
                    ResolutionFailure.METADATA_UNAVAILABLE,
                    "trusted Agent binding metadata is unavailable");
        }
        return resolved(binding, match, disclosable);
    }

    private static MateToolMeta findMetadata(TrustedBinding binding, List<MateToolMeta> metadata) {
        MateToolMeta match = null;
        for (MateToolMeta candidate : metadata == null ? List.<MateToolMeta>of() : metadata) {
            if (!binding.id().equals(candidate.toolId()) || !binding.name().equals(candidate.toolName())) {
                continue;
            }
            if (match != null) {
                throw failure(binding.name(), ResolutionFailure.CONFLICT, "duplicate business metadata");
            }
            match = candidate;
        }
        return match;
    }

    private static ResolvedTool resolved(TrustedBinding binding, MateToolMeta metadata, boolean disclosable) {
        if (metadata.inputSchema() == null) {
            throw failure(binding.name(), ResolutionFailure.SCHEMA_UNAVAILABLE, "business input schema is unavailable");
        }
        return new ResolvedTool(binding, metadata, disclosable);
    }

    private static Map<String, TrustedBinding> indexAgentBindings(List<BoundTool> tools) {
        Map<String, TrustedBinding> indexed = new LinkedHashMap<>();
        for (BoundTool tool : tools) {
            merge(indexed, TrustedBinding.agent(tool));
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, TrustedBinding> indexSkillBindings(List<SkillInfo> skills) {
        Map<String, TrustedBinding> indexed = new LinkedHashMap<>();
        for (SkillInfo skill : skills) {
            for (BoundTool tool : skill.bindingTools()) {
                merge(indexed, TrustedBinding.skill(tool, skill.id()));
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, TrustedBinding> indexDisclosableSkillBindings(List<SkillInfo> skills) {
        Map<String, TrustedBinding> indexed = new LinkedHashMap<>();
        for (SkillInfo skill : skills) {
            Object disabled = SkillLoader.parseFrontmatter(skill.content()).get("disable-model-invocation");
            if (Boolean.TRUE.equals(disabled)) {
                continue;
            }
            for (BoundTool tool : skill.bindingTools()) {
                merge(indexed, TrustedBinding.skill(tool, skill.id()));
            }
        }
        return Map.copyOf(indexed);
    }

    private boolean isDisclosable(String name) {
        return disclosableSkillBindings.containsKey(name);
    }

    private static void merge(Map<String, TrustedBinding> indexed, TrustedBinding candidate) {
        validateBinding(candidate);
        TrustedBinding previous = indexed.get(candidate.name());
        if (previous == null) {
            indexed.put(candidate.name(), candidate);
            return;
        }
        if (!previous.id().equals(candidate.id())) {
            throw failure(candidate.name(), ResolutionFailure.CONFLICT, "maps to different trusted ids");
        }
        indexed.put(candidate.name(), previous.merge(candidate));
    }

    private static void validateBinding(TrustedBinding binding) {
        if (binding.name() == null || binding.name().isBlank()) {
            throw new IllegalStateException("Business tool binding name is missing");
        }
        if (binding.id() == null
                || !ClawConstants.Tool.ID_PATTERN.matcher(binding.id()).matches()) {
            throw failure(binding.name(), ResolutionFailure.INVALID_BINDING, "has an invalid trusted id");
        }
        for (BuiltInToolName builtIn : BuiltInToolName.values()) {
            if (builtIn.externalName().equals(binding.name())) {
                throw failure(binding.name(), ResolutionFailure.CONFLICT, "conflicts with a framework tool");
            }
        }
    }

    private static void validateCrossScopeNames(
            Map<String, TrustedBinding> agentBindings, Map<String, TrustedBinding> skillBindings) {
        agentBindings.forEach((name, agentBinding) -> {
            TrustedBinding skillBinding = skillBindings.get(name);
            if (skillBinding != null && !agentBinding.id().equals(skillBinding.id())) {
                throw failure(name, ResolutionFailure.CONFLICT, "maps to different Agent and Skill ids");
            }
        });
    }

    private static ResolutionException failure(String name, ResolutionFailure reason, String detail) {
        return failure(name, reason, detail, null);
    }

    private static ResolutionException failure(
            String name, ResolutionFailure reason, String detail, RuntimeException cause) {
        String errorCode = reason.stableErrorCode();
        LOGGER.atError()
                .addKeyValue("operation", "mate.tool.resolve")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("resolutionReason", reason.name())
                .addKeyValue("toolName", name)
                .addKeyValue("detail", detail)
                .setCause(cause)
                .log("Failed to resolve managed business tool");
        return new ResolutionException(name, reason, detail, cause);
    }

    private static ResolutionException discoveryFailure(
            String operation, String scopeField, String scopeId, String name, RuntimeException cause) {
        ResolutionFailure reason = ResolutionFailure.METADATA_UNAVAILABLE;
        String detail = "business tool discovery failed";
        LOGGER.atError()
                .addKeyValue("operation", operation)
                .addKeyValue("errorCode", reason.stableErrorCode())
                .addKeyValue("resolutionReason", reason.name())
                .addKeyValue(scopeField, scopeId)
                .addKeyValue("detail", detail)
                .setCause(cause)
                .log("Failed to discover managed business tools");
        return new ResolutionException(name, reason, detail, cause);
    }

    enum ResolutionFailure {
        NAME_UNAVAILABLE,
        METADATA_UNAVAILABLE,
        SCHEMA_UNAVAILABLE,
        INVALID_BINDING,
        CONFLICT;

        private String stableErrorCode() {
            return switch (this) {
                case NAME_UNAVAILABLE -> ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE;
                case CONFLICT -> ClawConstants.RuntimeApi.TOOL_DEFINITION_CONFLICT_ERROR_CODE;
                case METADATA_UNAVAILABLE, SCHEMA_UNAVAILABLE, INVALID_BINDING ->
                    ClawConstants.RuntimeApi.TOOL_DEFINITION_UNAVAILABLE_ERROR_CODE;
            };
        }
    }

    static final class ResolutionException extends MateToolExecutionException implements StableErrorCode {

        private final ResolutionFailure reason;

        private ResolutionException(String name, ResolutionFailure reason, String detail, RuntimeException cause) {
            super(name, detail);
            this.reason = reason;
            if (cause != null) {
                initCause(cause);
            }
        }

        ResolutionFailure reason() {
            return reason;
        }

        @Override
        public String stableErrorCode() {
            return reason.stableErrorCode();
        }
    }

    record ResolvedTool(TrustedBinding binding, MateToolMeta metadata, boolean disclosable) {}

    record TrustedBinding(
            String id, String name, String displayName, String permission, String version, List<String> skillIds) {

        TrustedBinding {
            skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        }

        static TrustedBinding agent(BoundTool tool) {
            return new TrustedBinding(
                    tool.id(), tool.name(), tool.displayName(), tool.permission(), tool.version(), List.of());
        }

        static TrustedBinding skill(BoundTool tool, String skillId) {
            return new TrustedBinding(
                    tool.id(), tool.name(), tool.displayName(), tool.permission(), tool.version(), List.of(skillId));
        }

        TrustedBinding merge(TrustedBinding other) {
            List<String> sources = new ArrayList<>(skillIds);
            other.skillIds.stream().filter(id -> !sources.contains(id)).forEach(sources::add);
            String mergedDisplayName = java.util.Objects.equals(displayName, other.displayName) ? displayName : null;
            String mergedPermission = java.util.Objects.equals(permission, other.permission) ? permission : null;
            String mergedVersion = java.util.Objects.equals(version, other.version) ? version : null;
            return new TrustedBinding(id, name, mergedDisplayName, mergedPermission, mergedVersion, sources);
        }
    }
}
