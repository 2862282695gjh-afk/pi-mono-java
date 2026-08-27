/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;

/**
 * 在单个 Session 内协调 Mate 实时发现、直接 Skill 解析和缓存刷新。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolDiscovery {

    private final MateToolClient client;

    private final String agentId;

    private final Map<String, String> skillIdsByName;

    private final MateToolSessionCache sessionCache;

    public MateToolDiscovery(
            MateToolClient client,
            String agentId,
            Map<String, String> skillIdsByName,
            MateToolSessionCache sessionCache) {
        this.client = client;
        this.agentId = agentId;
        this.skillIdsByName = Map.copyOf(new TreeMap<>(skillIdsByName));
        this.sessionCache = sessionCache;
    }

    public List<MateToolMeta> listAgentTools() {
        List<MateToolMeta> tools = client.listAgentTools(agentId);
        sessionCache.updateSource(MateToolSource.agent(), tools);
        return tools;
    }

    public List<MateToolMeta> listSkillTools(String skillName) {
        String skillId = skillIdsByName.get(skillName);
        if (skillId == null) {
            throw new IllegalArgumentException("Unknown directly bound Skill: " + skillName);
        }
        List<MateToolMeta> tools = client.listSkillTools(skillId);
        sessionCache.updateSource(MateToolSource.skill(skillName), tools);
        return tools;
    }

    public String resolveToolId(String toolName) {
        return sessionCache.resolveOrRefresh(toolName, this::loadAllSources);
    }

    private Map<MateToolSource, List<MateToolMeta>> loadAllSources() {
        Map<MateToolSource, List<MateToolMeta>> discovered = new LinkedHashMap<>();
        discovered.put(MateToolSource.agent(), client.listAgentTools(agentId));
        skillIdsByName.forEach((name, id) -> discovered.put(MateToolSource.skill(name), client.listSkillTools(id)));
        return discovered;
    }
}
