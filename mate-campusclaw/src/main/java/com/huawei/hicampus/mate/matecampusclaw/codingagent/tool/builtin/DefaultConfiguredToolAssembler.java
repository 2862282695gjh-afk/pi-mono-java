/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

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
        return properties.toolsFor(entryPoint).stream()
                .map(name -> factory.create(name, context))
                .toList();
    }
}
