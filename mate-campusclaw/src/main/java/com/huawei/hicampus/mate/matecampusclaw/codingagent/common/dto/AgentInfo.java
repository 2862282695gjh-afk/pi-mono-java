/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto;

import java.util.List;

import lombok.Data;

/**
 * Agent metadata returned in the {@code result} field of
 * {@code GET /mate-service/v1/agents/{agentId}} on the Mate inner gateway.
 * Only {@code bindingTools} is consumed by the tool client.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class AgentInfo {

    private List<BindingTool> bindingTools;

    /**
     * A tool bound to an agent, as declared in {@link AgentInfo#getBindingTools()}.
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/18]
     * @since [br_eCampusCore 26.0.0]
     */
    @Data
    public static class BindingTool {

        private String toolId;

        private String version;
    }
}
