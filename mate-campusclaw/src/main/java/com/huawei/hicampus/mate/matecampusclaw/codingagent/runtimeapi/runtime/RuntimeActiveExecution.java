/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventStream;

/**
 * 单个 Session 当前唯一活动执行的进程内句柄。
 *
 * @param eventStream 与客户端连接解耦的 SSE 事件流
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record RuntimeActiveExecution(RuntimeEventStream eventStream) {}
