/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;

import org.springframework.stereotype.Service;

/**
 * 按已完成访问检查的 Session 标识读取一次完整持久化资源，不访问 Active Holder。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeSessionStatusService {
    private final RuntimeSessionRepository repository;

    public RuntimeSessionStatusService(RuntimeSessionRepository repository) {
        this.repository = repository;
    }

    public SessionCommandResultDTO query(String sessionId, String arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        var session = repository
                .find(sessionId)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
        return new SessionCommandResultDTO(session, false, null);
    }
}
