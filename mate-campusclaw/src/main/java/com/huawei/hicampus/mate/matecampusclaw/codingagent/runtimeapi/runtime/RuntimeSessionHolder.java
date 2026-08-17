/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template.AgentRuntimeSnapshotDTO;

/**
 * 单个 Runtime Session 的进程内执行对象。
 *
 * @param sessionId Session 标识
 * @param snapshot 固定的 Agent 发布快照
 * @param agent 执行 Agent
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record RuntimeSessionHolder(String sessionId, AgentRuntimeSnapshotDTO snapshot, Agent agent) {}
