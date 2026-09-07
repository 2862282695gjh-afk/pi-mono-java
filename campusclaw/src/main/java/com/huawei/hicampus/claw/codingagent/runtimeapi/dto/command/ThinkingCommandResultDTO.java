/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

/**
 * 承载深度思考开关的查询或修改结果及本次权威事件序号。
 *
 * @param thinking 当前开关
 * @param changed 本次是否实际修改
 * @param sourceEventSeq 本次追加的事件序号；查询和同值操作为 null
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record ThinkingCommandResultDTO(boolean thinking, boolean changed, Long sourceEventSeq)
        implements CommandResultDTO {}
