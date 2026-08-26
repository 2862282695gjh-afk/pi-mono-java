/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

/**
 * Agent 运行目录准备失败的稳定错误码。英文诊断信息只作为日志与 cause，公开边界
 * （HTTP/SSE、Child 工具结果、Cron 运行记录）按错误码映射，不透传诊断文本。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
public enum AgentRuntimeErrorCode {

    /** CampusMate 请求未到达或传输失败（超时、非 2xx、连接中断）。 */
    MATE_REQUEST_FAILED,

    /** CampusMate 响应体为空、非法 JSON、根节点不是对象或 result 缺失/形状不符。 */
    MATE_RESPONSE_INVALID,

    /** CampusMate 响应体超过配置的字节数上限。 */
    MATE_RESPONSE_TOO_LARGE,

    /** campusmate.runtime.base-url 缺失且本地无可用缓存。 */
    MATE_CONFIG_MISSING,

    /** 运行目录生成、校验或发布阶段失败。 */
    RUNTIME_PREPARE_FAILED
}
