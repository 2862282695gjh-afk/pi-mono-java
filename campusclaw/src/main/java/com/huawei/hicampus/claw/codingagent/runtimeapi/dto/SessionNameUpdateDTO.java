/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 数据库行锁内完成名称更新的结果，不包含历史事件。
 *
 * @param session 锁内确认的完整 Session，已应用本次名称与版本更新
 * @param changed 是否实际修改了名称
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionNameUpdateDTO(RuntimeSessionDTO session, boolean changed) {}
