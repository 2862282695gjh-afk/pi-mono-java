/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ToolOpsConfigTest {

    @Test
    void shouldProvideLocalImplementationsWhenCreatingBeans() {
        ToolOpsConfig cfg = new ToolOpsConfig();
        assertThat(cfg.readOperations()).isInstanceOf(LocalReadOperations.class);
        assertThat(cfg.lsOperations()).isInstanceOf(LocalLsOperations.class);
    }

    @Test
    void shouldPublishOnlyActiveFileOperationsWhenSpringAssemblesContext() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WorkspacePathResolver.class);
            context.register(ToolOpsConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(ReadOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(FindOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(GrepOperations.class)).hasSize(1);
            assertThat(context.getBeansOfType(LsOperations.class)).hasSize(1);
        }
    }

    @ParameterizedTest(name = "retired type {0} is absent")
    @ValueSource(
            strings = {
                "com.huawei.hicampus.claw.codingagent.tool.bash.BashExecutionResult",
                "com.huawei.hicampus.claw.codingagent.tool.bash.BashExecutor",
                "com.huawei.hicampus.claw.codingagent.tool.bash.BashExecutorOptions",
                "com.huawei.hicampus.claw.codingagent.tool.bash.BashTool",
                "com.huawei.hicampus.claw.codingagent.tool.bash.BashToolDetails",
                "com.huawei.hicampus.claw.codingagent.tool.bash.ShellResolver",
                "com.huawei.hicampus.claw.codingagent.tool.edit.DiffUtils",
                "com.huawei.hicampus.claw.codingagent.tool.edit.EditTool",
                "com.huawei.hicampus.claw.codingagent.tool.edit.EditToolDetails",
                "com.huawei.hicampus.claw.codingagent.tool.edit.FuzzyMatch",
                "com.huawei.hicampus.claw.codingagent.tool.write.WriteTool",
                "com.huawei.hicampus.claw.codingagent.tool.ops.BashExecOptions",
                "com.huawei.hicampus.claw.codingagent.tool.ops.BashOperations",
                "com.huawei.hicampus.claw.codingagent.tool.ops.BashResult",
                "com.huawei.hicampus.claw.codingagent.tool.ops.EditOperations",
                "com.huawei.hicampus.claw.codingagent.tool.ops.LocalBashOperations",
                "com.huawei.hicampus.claw.codingagent.tool.ops.LocalEditOperations",
                "com.huawei.hicampus.claw.codingagent.tool.ops.LocalWriteOperations",
                "com.huawei.hicampus.claw.codingagent.tool.ops.WriteOperations",
                "com.huawei.hicampus.claw.codingagent.util.FileMutationQueue",
                "com.huawei.hicampus.claw.codingagent.util.PathUtils"
            })
    void shouldOmitRetiredMutationAndShellTypeFromClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }
}
