/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptBuilder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillExpander;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolCatalog;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolSelection;

/**
 * Entry-session collaborators a delegated child session must share so entry
 * and child Agents run through the same assembly chain (AI service, prompt
 * builder, skill loader/expander, tool catalog and visibility selection).
 *
 * <p>The runtime-varying parts (sandbox-aware skill loader, per-run tool
 * selection, effective local tools) come from the entry site; Spring-static
 * parts are injected into {@link LocalAgentDispatcher} directly.
 *
 * @param aiService     shared LLM service
 * @param modelRegistry shared model registry
 * @param promptBuilder shared system prompt builder
 * @param skillLoader   sandbox-aware skill loader of the entry run
 * @param skillExpander sandbox-aware skill expander of the entry run
 * @param localTools    locally visible tools of the entry run
 * @param toolCatalog   tool catalog, {@code null} when the entry run has none
 * @param toolSelection tool visibility selection of the entry run
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record DelegationWiring(
        CampusClawAiService aiService,
        ModelRegistry modelRegistry,
        SystemPromptBuilder promptBuilder,
        SkillLoader skillLoader,
        SkillExpander skillExpander,
        List<com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool> localTools,
        ToolCatalog toolCatalog,
        ToolSelection toolSelection) {

    public DelegationWiring {
        localTools = localTools == null ? List.of() : List.copyOf(localTools);
    }
}
