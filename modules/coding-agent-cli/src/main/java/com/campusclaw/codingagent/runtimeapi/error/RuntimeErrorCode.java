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
    SESSION_BUSY("该 Session 正在处理另一条用户消息。", "The Session is processing another user message."),
    SESSION_INITIALIZATION_FAILED("Session 初始化失败。", "Session initialization failed."),
    SESSION_DELETE_FAILED("Session 删除失败。", "Session deletion failed."),
    INVALID_EVENT_REQUEST("用户事件请求内容不符合约束。", "The user event request does not satisfy the required constraints."),
    EVENT_ACCEPTANCE_FAILED("用户事件接收失败。", "User event acceptance failed."),
    SESSION_EXECUTION_FAILED("Session 执行失败。", "Session execution failed."),
    INVALID_EVENT_LIST_QUERY("事件分页参数不符合约束。", "The event pagination parameters are invalid."),
    EVENT_LIST_FAILED("Session 持久化事件读取失败。", "Failed to list persisted events for the Session."),
    INVALID_MODEL_REQUEST("模型切换请求不符合约束。", "The model change request is invalid."),
    INVALID_THINKING_REQUEST("深度思考设置请求不符合约束。", "The deep-thinking setting request is invalid."),
    MODEL_NOT_AVAILABLE("指定模型当前不可用于该 Session。", "The specified model is not available for this Session."),
    THINKING_NOT_SUPPORTED("当前模型不支持深度思考。", "The current model does not support deep thinking."),
    IF_MATCH_REQUIRED("修改 Session 配置必须携带 If-Match。", "If-Match is required to modify Session configuration."),
    SESSION_VERSION_MISMATCH("Session 已发生变化，请刷新后重试。", "The Session has changed. Refresh it before retrying."),
    SESSION_MODEL_UPDATE_FAILED("Session 模型更新失败。", "Failed to update the Session model."),
    SESSION_THINKING_UPDATE_FAILED("Session 深度思考设置更新失败。", "Failed to update the Session deep-thinking setting."),
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
