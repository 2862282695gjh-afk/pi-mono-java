/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto;

import java.util.List;

import lombok.Data;

/**
 * Mate 内网网关 {@code GET /mate-service/v1/agents/{agentId}} 返回信封中
 * {@code result} 字段的 Agent 元数据。工具客户端仅消费 {@code bindingTools}。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class AgentInfo {
    private List<BindingTool> bindingTools;

    /**
     * {@link AgentInfo#getBindingTools()} 中声明的、绑定到 Agent 的工具。
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
