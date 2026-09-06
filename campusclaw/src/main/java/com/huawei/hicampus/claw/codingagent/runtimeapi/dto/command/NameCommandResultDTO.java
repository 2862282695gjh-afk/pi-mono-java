/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

/**
 * Name 命令的内部结果，由后续应用层转换为独立响应 VO。
 *
 * @param displayName 当前会话名称；未设置时为 null
 * @param changed 是否实际修改了名称
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
public record NameCommandResultDTO(String displayName, boolean changed) implements CommandResultDTO {}
