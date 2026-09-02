/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.command;

/**
 * Slash Command 向宿主返回文本结果的最小端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface SlashCommandOutput {
    void println(String message);
}
