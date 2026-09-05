/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.StatusCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.stereotype.Service;

/**
 * 读取应用层完成访问检查后取得的持久化 Session 快照，不访问 Active Holder。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionStatusService {
    public StatusCommandResultDTO query(CommandSessionSnapshotDTO session, String arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return new StatusCommandResultDTO(session.state(), session.modelId(), session.thinking());
    }
}
