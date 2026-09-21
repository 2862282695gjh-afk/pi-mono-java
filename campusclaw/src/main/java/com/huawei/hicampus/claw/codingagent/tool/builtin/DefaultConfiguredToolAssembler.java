/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.AgentTool;

import org.springframework.stereotype.Component;

/**
 * 使用关闭工厂实现启动期 profile 到 Session 工具实例的映射。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class DefaultConfiguredToolAssembler implements ConfiguredToolAssembler {

    private final BuiltInToolProperties properties;

    private final BuiltInToolFactory factory;

    public DefaultConfiguredToolAssembler(BuiltInToolProperties properties, BuiltInToolFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    @Override
    public List<AgentTool> assemble(ToolEntryPoint entryPoint, ToolAssemblyContext context) {
        List<BuiltInToolName> configured = properties.toolsFor(entryPoint);
        if (configured.isEmpty()) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        configured.stream().map(name -> factory.create(name, context)).forEach(tools::add);
        if (context.mateToolSessionState() != null) {
            tools.addAll(context.mateToolSessionState().createAgentToolAdapters());
        }
        validateNames(tools);
        return List.copyOf(tools);
    }

    private static void validateNames(List<AgentTool> tools) {
        Set<String> reserved = new HashSet<>();
        for (BuiltInToolName builtIn : BuiltInToolName.values()) {
            reserved.add(builtIn.externalName());
        }
        Set<String> seen = new HashSet<>();
        for (AgentTool tool : tools) {
            String name = tool.name();
            if (!seen.add(name)) {
                throw new IllegalStateException("Duplicate assembled tool name: " + name);
            }
            if (!(tool instanceof com.huawei.hicampus.claw.codingagent.tool.mate.MateToolAdapter)) {
                continue;
            }
            if (reserved.contains(name)) {
                throw new IllegalStateException("Business tool name conflicts with framework tool: " + name);
            }
        }
    }
}
