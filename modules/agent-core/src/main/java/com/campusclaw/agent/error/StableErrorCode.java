/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.error;

/**
 * 携带稳定错误码的异常契约。实现异常的 {@link #stableErrorCode()} 返回机器稳定的
 * 错误码，供公开边界（HTTP/SSE、Child 工具结果、Cron 运行记录）映射与展示；
 * 异常自身的英文消息仅作为日志与诊断用途，不得直接作为对外文案。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
public interface StableErrorCode {

    /**
     * 返回机器稳定的错误码。
     *
     * @return 稳定错误码，例如 {@code MATE_RESPONSE_INVALID}
     */
    String stableErrorCode();
}
