/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * Session 当前模型与实时可选模型的成功结果 VO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class AvailableModelsResponseVO {
    @JsonProperty("current_model_id")
    private final String currentModelId;

    private final List<String> models;

    public AvailableModelsResponseVO(String currentModelId, List<String> models) {
        this.currentModelId = currentModelId;
        this.models = List.copyOf(models);
    }
}
