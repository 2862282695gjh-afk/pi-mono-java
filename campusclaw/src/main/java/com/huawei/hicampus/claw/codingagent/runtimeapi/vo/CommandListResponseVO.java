/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;

/**
 * 当前可调用命令形态的轻量清单，不暴露内部准入和执行信息。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public final class CommandListResponseVO {
    private final List<DescriptorResponseVO> commands;

    public CommandListResponseVO(List<DescriptorResponseVO> commands) {
        this.commands = List.copyOf(commands);
    }

    /**
     * 单个命令的公开描述，输入不存在时省略该字段。
     */
    @Getter
    public static final class DescriptorResponseVO {
        private final String name;

        private final String kind;

        private final String description;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final InputResponseVO input;

        public DescriptorResponseVO(String name, String kind, String description, InputResponseVO input) {
            this.name = name;
            this.kind = kind;
            this.description = description;
            this.input = input;
        }
    }

    /**
     * 仅包含展示提示与附件能力；false 附件能力按契约省略。
     */
    @Getter
    public static final class InputResponseVO {
        private final String hint;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private final boolean acceptsFiles;

        public InputResponseVO(String hint, boolean acceptsFiles) {
            this.hint = hint;
            this.acceptsFiles = acceptsFiles;
        }
    }
}
