/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate.CallMateTool;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for the Mate tool client and AgentTools.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * mate:
 *   tool:
 *     enabled: true
 *     base-url: http://127.0.0.1:9999
 *     x-hw-id: hw-id-001
 *     x-hw-appkey: hw-key-001
 * </pre>
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
@ConfigurationProperties(prefix = "mate.tool")
public class MateToolProperties {

    /**
     * Master enable switch; when false neither Mate AgentTool is registered.
     */
    private boolean enabled = true;

    /**
     * Base URL of the Mate tool server.
     */
    private String baseUrl = "http://127.0.0.1:9999";

    /**
     * X-HW-ID credential header value.
     */
    private String xHwId = "";

    /**
     * X-HW-APPKEY credential header value.
     */
    private String xHwAppKey = "";

    /**
     * Approval callback for tools whose permission is "ask"; defaults to deny
     * (fail-closed) so an unconfigured interactive UI never auto-approves.
     */
    private CallMateTool.MateApprovalUI approvalUi = (tool, args, description) -> false;
}
