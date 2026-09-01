/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.hicampus.claw.codingagent.tool.bash.BashExecutor;
import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ToolOpsConfigTest {

    @Test
    void beansProvideLocalImplementations() {
        ToolOpsConfig cfg = new ToolOpsConfig();
        assertThat(cfg.readOperations()).isInstanceOf(LocalReadOperations.class);
        assertThat(cfg.lsOperations()).isInstanceOf(LocalLsOperations.class);
    }

    @Test
    void springAssemblyDoesNotPublishDisabledMutationOrShellOperations() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WorkspacePathResolver.class);
            context.register(ToolOpsConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(ReadOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(FindOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(GrepOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(LsOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(WriteOperations.class)).isEmpty();
            assertThat(context.getBeansOfType(EditOperations.class)).isEmpty();
            assertThat(context.getBeansOfType(BashOperations.class)).isEmpty();
            assertThat(context.getBeansOfType(BashExecutor.class)).isEmpty();
        }
    }
}
