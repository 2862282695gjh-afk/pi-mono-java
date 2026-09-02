/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定并严格校验三个入口的内置工具配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusclaw.tools")
public class BuiltInToolProperties implements InitializingBean {

    private List<String> runtime = externalNames(List.of(
            BuiltInToolName.READ,
            BuiltInToolName.FIND,
            BuiltInToolName.GREP,
            BuiltInToolName.LS,
            BuiltInToolName.CRON,
            BuiltInToolName.LIST_MATE_TOOLS,
            BuiltInToolName.CALL_MATE_TOOL,
            BuiltInToolName.AGENT));

    private List<String> cron = externalNames(List.of(
            BuiltInToolName.READ,
            BuiltInToolName.FIND,
            BuiltInToolName.GREP,
            BuiltInToolName.LS,
            BuiltInToolName.LIST_MATE_TOOLS,
            BuiltInToolName.CALL_MATE_TOOL,
            BuiltInToolName.AGENT));

    private List<String> childAgent = externalNames(List.of(
            BuiltInToolName.READ,
            BuiltInToolName.FIND,
            BuiltInToolName.GREP,
            BuiltInToolName.LS,
            BuiltInToolName.LIST_MATE_TOOLS,
            BuiltInToolName.CALL_MATE_TOOL));

    public List<String> getRuntime() {
        return runtime;
    }

    public void setRuntime(List<String> runtime) {
        this.runtime = copy(runtime);
    }

    public List<String> getCron() {
        return cron;
    }

    public void setCron(List<String> cron) {
        this.cron = copy(cron);
    }

    public List<String> getChildAgent() {
        return childAgent;
    }

    public void setChildAgent(List<String> childAgent) {
        this.childAgent = copy(childAgent);
    }

    /**
     * 返回入口对应的不可变工具枚举列表。
     *
     * @param entryPoint Session 创建入口
     * @return 保持配置顺序的工具列表
     */
    public List<BuiltInToolName> toolsFor(ToolEntryPoint entryPoint) {
        List<String> configured =
                switch (entryPoint) {
                    case RUNTIME -> runtime;
                    case CRON -> cron;
                    case CHILD_AGENT -> childAgent;
                };
        return configured.stream().map(BuiltInToolName::fromExternalName).toList();
    }

    @Override
    public void afterPropertiesSet() {
        validate("runtime", runtime);
        validate("cron", cron);
        validate("child-agent", childAgent);
        runtime = List.copyOf(runtime);
        cron = List.copyOf(cron);
        childAgent = List.copyOf(childAgent);
    }

    private static void validate(String property, List<String> configured) {
        var seen = new HashSet<String>();
        for (String name : configured) {
            BuiltInToolName.fromExternalName(name);
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate built-in tool in " + property + ": " + name);
            }
        }
    }

    private static List<String> externalNames(List<BuiltInToolName> tools) {
        return new ArrayList<>(tools.stream().map(BuiltInToolName::externalName).toList());
    }

    private static List<String> copy(List<String> configured) {
        return configured == null ? new ArrayList<>() : new ArrayList<>(configured);
    }
}
