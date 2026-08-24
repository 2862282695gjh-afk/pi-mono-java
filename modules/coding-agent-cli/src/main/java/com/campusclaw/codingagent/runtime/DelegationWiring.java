/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.List;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.codingagent.prompt.SystemPromptBuilder;
import com.campusclaw.codingagent.skill.SkillExpander;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.campusclaw.codingagent.tool.catalog.ToolCatalog;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;

/**
 * 入口会话中必须与被委派子会话共享的协作者，使入口与子 Agent 走同一条
 * 组装链（AI 服务、提示词构建器、Skill 加载/展开器、工具目录与可见性选择）。
 *
 * <p>随运行变化的部分（感知沙箱的 Skill 加载器、按运行的工具选择、有效
 * 本地工具）来自入口现场；Spring 静态部分直接注入 {@link LocalAgentDispatcher}。
 *
 * @param aiService 共享的 LLM 服务
 * @param modelRegistry 共享的模型注册表
 * @param promptBuilder 共享的系统提示词构建器
 * @param skillLoader 入口运行的感知沙箱 Skill 加载器
 * @param skillExpander 入口运行的感知沙箱 Skill 展开器
 * @param localTools 入口运行的本地可见工具
 * @param toolCatalog 工具目录，入口运行没有时为 {@code null}
 * @param toolSelection 入口运行的工具可见性选择
 * @param mateToolsetFactory Mate 会话私有工具对工厂；入口没有时为 {@code null}
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
        List<com.campusclaw.agent.tool.AgentTool> localTools,
        ToolCatalog toolCatalog,
        ToolSelection toolSelection,
        com.campusclaw.codingagent.tool.mate.MateToolsetFactory mateToolsetFactory) {

    public DelegationWiring {
        localTools = localTools == null ? List.of() : List.copyOf(localTools);
    }
}
