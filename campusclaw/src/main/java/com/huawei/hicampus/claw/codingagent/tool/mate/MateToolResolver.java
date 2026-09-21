/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

/** 在单个 Session 内按可信 Agent 与 Skill 绑定解析 Mate 工具定义。 */
final class MateToolResolver {

    private final MateToolClient client;

    private final String agentId;

    private final PreparedAgentRuntime runtime;

    private volatile Map<String, TrustedBinding> agentBindings;

    private volatile Map<String, TrustedBinding> skillBindings;

    private final Map<String, ResolvedTool> skillCache = new ConcurrentHashMap<>();

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
                .orElseThrow(() -> failure(name, "not directly bound to the current Agent"));
    }

    ResolvedTool resolveSkill(String name) {
        ensureInitialized();
        TrustedBinding binding = skillBindings.get(name);
        if (binding == null) {
            throw failure(name, "not bound through a directly bound Skill");
        }
        return skillCache.computeIfAbsent(name, ignored -> loadSkillTool(binding));
    }

    private List<ResolvedTool> loadAgentTools() {
        if (agentBindings.isEmpty()) {
            return List.of();
        }
        List<MateToolMeta> metadata = client.listAgentTools(agentId);
        List<ResolvedTool> resolved = new ArrayList<>();
        for (TrustedBinding binding : agentBindings.values()) {
            resolved.add(resolveMetadata(binding, metadata));
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
            validateCrossScopeNames(agents, skills);
            skillBindings = skills;
            agentBindings = agents;
        }
    }

    private ResolvedTool loadSkillTool(TrustedBinding binding) {
        for (String skillId : binding.skillIds()) {
            List<MateToolMeta> metadata = client.listSkillTools(skillId);
            MateToolMeta match = findMetadata(binding, metadata);
            if (match != null) {
                return resolved(binding, match);
            }
        }
        throw failure(binding.name(), "trusted Skill binding metadata is unavailable");
    }

    private static ResolvedTool resolveMetadata(TrustedBinding binding, List<MateToolMeta> metadata) {
        MateToolMeta match = findMetadata(binding, metadata);
        if (match == null) {
            throw failure(binding.name(), "trusted Agent binding metadata is unavailable");
        }
        return resolved(binding, match);
    }

    private static MateToolMeta findMetadata(TrustedBinding binding, List<MateToolMeta> metadata) {
        MateToolMeta match = null;
        for (MateToolMeta candidate : metadata == null ? List.<MateToolMeta>of() : metadata) {
            if (!binding.id().equals(candidate.toolId()) || !binding.name().equals(candidate.toolName())) {
                continue;
            }
            if (match != null) {
                throw failure(binding.name(), "duplicate business metadata");
            }
            match = candidate;
        }
        return match;
    }

    private static ResolvedTool resolved(TrustedBinding binding, MateToolMeta metadata) {
        if (metadata.inputSchema() == null) {
            throw failure(binding.name(), "business input schema is unavailable");
        }
        return new ResolvedTool(binding, metadata);
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

    private static void merge(Map<String, TrustedBinding> indexed, TrustedBinding candidate) {
        validateBinding(candidate);
        TrustedBinding previous = indexed.get(candidate.name());
        if (previous == null) {
            indexed.put(candidate.name(), candidate);
            return;
        }
        if (!previous.id().equals(candidate.id())) {
            throw failure(candidate.name(), "maps to different trusted ids");
        }
        indexed.put(candidate.name(), previous.merge(candidate));
    }

    private static void validateBinding(TrustedBinding binding) {
        if (binding.name() == null || binding.name().isBlank()) {
            throw new IllegalStateException("Business tool binding name is missing");
        }
        if (binding.id() == null
                || !ClawConstants.Tool.ID_PATTERN.matcher(binding.id()).matches()) {
            throw failure(binding.name(), "has an invalid trusted id");
        }
        for (BuiltInToolName builtIn : BuiltInToolName.values()) {
            if (builtIn.externalName().equals(binding.name())) {
                throw failure(binding.name(), "conflicts with a framework tool");
            }
        }
    }

    private static void validateCrossScopeNames(
            Map<String, TrustedBinding> agentBindings, Map<String, TrustedBinding> skillBindings) {
        agentBindings.forEach((name, agentBinding) -> {
            TrustedBinding skillBinding = skillBindings.get(name);
            if (skillBinding != null && !agentBinding.id().equals(skillBinding.id())) {
                throw failure(name, "maps to different Agent and Skill ids");
            }
        });
    }

    private static MateToolExecutionException failure(String name, String detail) {
        return new MateToolExecutionException(name, detail);
    }

    record ResolvedTool(TrustedBinding binding, MateToolMeta metadata) {}

    record TrustedBinding(String id, String name, String displayName, String permission, List<String> skillIds) {

        TrustedBinding {
            skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        }

        static TrustedBinding agent(BoundTool tool) {
            return new TrustedBinding(tool.id(), tool.name(), tool.displayName(), tool.permission(), List.of());
        }

        static TrustedBinding skill(BoundTool tool, String skillId) {
            return new TrustedBinding(tool.id(), tool.name(), tool.displayName(), tool.permission(), List.of(skillId));
        }

        TrustedBinding merge(TrustedBinding other) {
            List<String> sources = new ArrayList<>(skillIds);
            other.skillIds.stream().filter(id -> !sources.contains(id)).forEach(sources::add);
            String mergedDisplayName = java.util.Objects.equals(displayName, other.displayName) ? displayName : null;
            String mergedPermission = java.util.Objects.equals(permission, other.permission) ? permission : null;
            return new TrustedBinding(id, name, mergedDisplayName, mergedPermission, sources);
        }
    }
}
