/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

import java.util.List;

/**
 * Model 查询或切换的内部结果，由后续应用层转换为响应 VO。
 *
 * @param currentModelId 操作后的当前模型标识
 * @param models 当前可用模型标识，保留配置顺序
 * @param changed 本次是否修改模型
 * @param sourceEventSeq 本次追加的最后一条领域 Entry 序号；无追加时为 null
 * @version [br_eCampusCore 26.0.0, 2026/09/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record ModelCommandResultDTO(String currentModelId, List<String> models, boolean changed, Long sourceEventSeq)
        implements CommandResultDTO {
    public ModelCommandResultDTO {
        models = List.copyOf(models);
    }
}
