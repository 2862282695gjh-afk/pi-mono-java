/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto.command;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * 携带查询或事务返回的完整 Session，供应用层复用资源与 ETag 投影。
 *
 * @param session 本次权威 Session；不得使用旧目录字段或提交后查询重建
 * @param changed 本次是否实际更新，仅供内部使用
 * @param sourceEventSeq 本次最后一条领域 Entry 序号；无追加时为 null，仅供内部使用
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionCommandResultDTO(RuntimeSessionDTO session, boolean changed, Long sourceEventSeq)
        implements CommandResultDTO {}
