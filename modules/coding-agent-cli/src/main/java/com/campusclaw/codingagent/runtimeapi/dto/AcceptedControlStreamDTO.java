/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;

/**
 * 控制事件受理结果及由调用方返回和关闭的请求范围事件流。
 *
 * @param stream 已排入完整控制回执并绑定权威结果的事件流
 * @param acceptance 已提交控制事件及固定执行目标
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record AcceptedControlStreamDTO(RuntimeEventStream stream, AcceptedControlDTO acceptance) {}
