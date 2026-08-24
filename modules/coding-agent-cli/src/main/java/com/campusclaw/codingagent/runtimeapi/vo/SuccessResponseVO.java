/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * 公司普通成功响应的独立开发兼容 VO。
 *
 * @param <T> 结果类型
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class SuccessResponseVO<T> {
    @JsonProperty("resCode")
    private final String resCode;

    @JsonProperty("resMsg")
    private final String resMsg;

    @JsonProperty("result")
    private final T result;

    public SuccessResponseVO(String resCode, String resMsg, T result) {
        this.resCode = resCode;
        this.resMsg = resMsg;
        this.result = result;
    }
}
