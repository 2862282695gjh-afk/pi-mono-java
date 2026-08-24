/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.workspace;

/**
 * 表示不应向模型泄露物理路径信息的工作区访问失败。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class WorkspaceAccessException extends RuntimeException {

    public WorkspaceAccessException(String message) {
        super(message);
    }

    public WorkspaceAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
