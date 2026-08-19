/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * 不包含 result 字段的公司异常响应 VO。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Getter
public class ErrorResponseVO {
    @JsonProperty("resCode")
    private final String resCode;

    @JsonProperty("resMsg")
    private final String resMsg;

    public ErrorResponseVO(String resCode, String resMsg) {
        this.resCode = resCode;
        this.resMsg = resMsg;
    }
}
