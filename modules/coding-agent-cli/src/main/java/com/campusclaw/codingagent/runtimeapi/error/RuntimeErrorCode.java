/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.error;

/**
 * Runtime HTTP V1 的稳定错误码和中英文消息。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public enum RuntimeErrorCode {
    INVALID_AGENT_ID("agent_id 格式不正确。", "The agent_id format is invalid."),
    INVALID_SESSION_ID("session_id 格式不正确。", "The session_id format is invalid."),
    UNAUTHENTICATED("调用方身份认证失败。", "Caller authentication failed."),
    AUTH_CREDENTIAL_CONFLICT("请求不能同时携带 JWT 和 APPKEY 凭据。", "JWT and APPKEY credentials must not be supplied together."),
    FORBIDDEN("当前调用方无权访问该资源。", "The caller is not allowed to access this resource."),
    AGENT_NOT_FOUND("指定的 Agent 不存在。", "The specified Agent does not exist."),
    AGENT_NOT_AVAILABLE("指定的 Agent 当前不可用。", "The specified Agent is currently unavailable."),
    AGENT_MODEL_NOT_CONFIGURED("该 Agent 未配置有效的默认模型。", "The Agent has no valid default model configured."),
    SESSION_NOT_FOUND("指定的 Session 不存在。", "The specified Session does not exist."),
    SESSION_INITIALIZATION_FAILED("Session 初始化失败。", "Session initialization failed."),
    SESSION_DELETE_FAILED("Session 删除失败。", "Session deletion failed."),
    MANAGER_UNAVAILABLE("Model Manager 暂时不可用，请稍后重试。", "Model Manager is temporarily unavailable. Try again later."),
    INTERNAL_ERROR("服务内部错误。", "Internal service error.");

    private final String chineseMessage;

    private final String englishMessage;

    RuntimeErrorCode(String chineseMessage, String englishMessage) {
        this.chineseMessage = chineseMessage;
        this.englishMessage = englishMessage;
    }

    public String message(boolean chinese) {
        return chinese ? chineseMessage : englishMessage;
    }
}
