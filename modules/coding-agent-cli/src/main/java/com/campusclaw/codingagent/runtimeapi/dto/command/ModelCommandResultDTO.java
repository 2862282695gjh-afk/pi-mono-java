/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto.command;

import java.util.List;

/**
 * Model 查询的内部模型清单，由应用层复用既有模型清单响应 VO。
 *
 * @param currentModelId 操作后的当前模型标识
 * @param models 当前可用模型标识，保留配置顺序
 * @version [br_eCampusCore 26.0.0, 2026/09/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record ModelCommandResultDTO(String currentModelId, List<String> models) implements CommandResultDTO {
    public ModelCommandResultDTO {
        models = List.copyOf(models);
    }
}
