/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;

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
                "com.campusclaw.codingagent.tool.bash.BashExecutionResult",
                "com.campusclaw.codingagent.tool.bash.BashExecutor",
                "com.campusclaw.codingagent.tool.bash.BashExecutorOptions",
                "com.campusclaw.codingagent.tool.bash.BashTool",
                "com.campusclaw.codingagent.tool.bash.BashToolDetails",
                "com.campusclaw.codingagent.tool.bash.ShellResolver",
                "com.campusclaw.codingagent.tool.edit.DiffUtils",
                "com.campusclaw.codingagent.tool.edit.EditTool",
                "com.campusclaw.codingagent.tool.edit.EditToolDetails",
                "com.campusclaw.codingagent.tool.edit.FuzzyMatch",
                "com.campusclaw.codingagent.tool.write.WriteTool",
                "com.campusclaw.codingagent.tool.ops.BashExecOptions",
                "com.campusclaw.codingagent.tool.ops.BashOperations",
                "com.campusclaw.codingagent.tool.ops.BashResult",
                "com.campusclaw.codingagent.tool.ops.EditOperations",
                "com.campusclaw.codingagent.tool.ops.LocalBashOperations",
                "com.campusclaw.codingagent.tool.ops.LocalEditOperations",
                "com.campusclaw.codingagent.tool.ops.LocalWriteOperations",
                "com.campusclaw.codingagent.tool.ops.WriteOperations",
                "com.campusclaw.codingagent.util.FileMutationQueue",
                "com.campusclaw.codingagent.util.PathUtils"
            })
    void shouldOmitRetiredMutationAndShellTypeFromClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }
}
