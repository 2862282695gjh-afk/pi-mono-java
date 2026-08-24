/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.cron.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Cron Job 的封闭执行载荷。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = CronPayload.AgentPrompt.class, name = "agent_prompt")})
public sealed interface CronPayload {

    /** 以 Job 自动绑定的受管 Agent 执行提示词。 */
    record AgentPrompt(String agentId, String prompt) implements CronPayload {}
}
