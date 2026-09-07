/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.util.List;

/**
 * 数据库行锁内读取的 Session 与当前分支，仅用于本次压缩准备。
 *
 * @param session 同一观察时点的 Session 数据
 * @param entries 当前分支完整 Entry 列表；忙状态不读取历史
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeCompactionSnapshotDTO(RuntimeSessionDTO session, List<RuntimeEntryDTO> entries) {}
