/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.ops;

import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供本地文件系统操作实现和共享文件工具依赖的 Spring 配置。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Configuration
public class ToolOpsConfig {

    @Bean
    public ReadOperations readOperations() {
        return new LocalReadOperations();
    }

    @Bean
    public LsOperations lsOperations() {
        return new LocalLsOperations();
    }

    @Bean
    public FindOperations findOperations(WorkspacePathResolver pathResolver) {
        return new LocalFindOperations(pathResolver);
    }

    @Bean
    public GrepOperations grepOperations(WorkspacePathResolver pathResolver) {
        return new LocalGrepOperations(pathResolver);
    }
}
