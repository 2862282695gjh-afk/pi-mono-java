/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

/**
 * 表示受管业务工具解析、参数校验或远端执行失败。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolExecutionException extends RuntimeException {

    public MateToolExecutionException(String tool, String detail) {
        super("Managed business tool '" + tool + "' failed: " + detail);
    }
}
