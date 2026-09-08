/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import com.huawei.hicampus.claw.codingagent.runtimeapi.web.json.SubmitSessionEventRequestDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Session Events v2 的单事件请求外壳。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(using = SubmitSessionEventRequestDeserializer.class)
public class SubmitSessionEventRequestVO {
    @Valid
    @NotNull
    private SessionUserEventRequestVO event;
}
