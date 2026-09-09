/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;

/**
 * 单次 Runtime 压缩的内部终态数据，不携带摘要、凭据或公共命令身份。
 *
 * @param compacted 是否成功保存了本次压缩
 * @param sourceEventSeq 本次权威压缩 Entry 序号，无变化时为 null
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeCompactionResultDTO(boolean compacted, Long sourceEventSeq) implements CommandResultDTO {}
