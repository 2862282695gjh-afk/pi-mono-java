/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.List;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.codingagent.tool.question.ShowcaseConfirmationProperties;

import org.springframework.beans.factory.annotation.Autowired;
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

    private final ShowcaseConfirmationProperties showcase;

    public DefaultConfiguredToolAssembler(BuiltInToolProperties properties, BuiltInToolFactory factory) {
        this(properties, factory, new ShowcaseConfirmationProperties());
    }

    @Autowired
    public DefaultConfiguredToolAssembler(
            BuiltInToolProperties properties, BuiltInToolFactory factory, ShowcaseConfirmationProperties showcase) {
        this.properties = properties;
        this.factory = factory;
        this.showcase = showcase;
    }

    @Override
    public List<AgentTool> assemble(ToolEntryPoint entryPoint, ToolAssemblyContext context) {
        return properties.toolsFor(entryPoint).stream()
                .filter(name -> !disabledQuestion(name))
                .map(name -> factory.create(name, context))
                .toList();
    }

    private boolean disabledQuestion(BuiltInToolName name) {
        return showcase.isConfirmWithUserEnabled() && name == BuiltInToolName.ASK_USER_QUESTION;
    }
}
