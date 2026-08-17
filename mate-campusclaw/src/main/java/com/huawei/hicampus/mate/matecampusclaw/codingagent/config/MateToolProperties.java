/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.CallMateTool;

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
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
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

    /**
     * Approval callback for tools whose permission is "ask"; defaults to deny
     * (fail-closed) so an unconfigured interactive UI never auto-approves.
     */
    private CallMateTool.MateApprovalUI approvalUI = (tool, args, description) -> false;

    /**
     * Returns whether the Mate tools are enabled.
     *
     * @return true when enabled
     */
    public boolean getEnabled() {
        return enabled;
    }

    /**
     * Sets whether the Mate tools are enabled.
     *
     * @param enabled the enabled flag
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the Mate tool server base URL.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Sets the Mate tool server base URL.
     *
     * @param baseUrl the base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Returns the X-HW-ID credential.
     *
     * @return the X-HW-ID value
     */
    public String getXHwId() {
        return xHwId;
    }

    /**
     * Sets the X-HW-ID credential.
     *
     * @param xHwId the X-HW-ID value
     */
    public void setXHwId(String xHwId) {
        this.xHwId = xHwId;
    }

    /**
     * Returns the X-HW-APPKEY credential.
     *
     * @return the X-HW-APPKEY value
     */
    public String getXHwAppKey() {
        return xHwAppKey;
    }

    /**
     * Sets the X-HW-APPKEY credential.
     *
     * @param xHwAppKey the X-HW-APPKEY value
     */
    public void setXHwAppKey(String xHwAppKey) {
        this.xHwAppKey = xHwAppKey;
    }

    /**
     * Returns the approval callback for "ask" tools.
     *
     * @return the approval UI callback
     */
    public CallMateTool.MateApprovalUI getApprovalUi() {
        return approvalUI;
    }

    /**
     * Sets the approval callback for "ask" tools.
     *
     * @param approvalUi the approval UI callback
     */
    public void setApprovalUi(CallMateTool.MateApprovalUI approvalUi) {
        this.approvalUi = approvalUi;
    }
}
