/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.NameCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 查询与规范化会话名称，并委托数据库事务执行 last-commit-wins 更新。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SessionNamingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionNamingService.class);

    private final RuntimeSessionRepository repository;

    private final Clock clock;

    public SessionNamingService(RuntimeSessionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public NameCommandResultDTO execute(String sessionId, String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            var session = repository
                    .find(sessionId)
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
            return new NameCommandResultDTO(session.getDisplayName(), false);
        }
        String displayName = normalizeName(arguments);
        try {
            var update = repository
                    .updateName(sessionId, displayName, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
            return new NameCommandResultDTO(update.displayName(), update.changed());
        } catch (RuntimeApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("CampusClaw failure: operation=runtime.session.name, errorCode=SESSION_NAME_UPDATE_FAILED");
            throw new RuntimeApiException(RuntimeErrorCode.SESSION_NAME_UPDATE_FAILED);
        }
    }

    private String normalizeName(String arguments) {
        if (ClawConstants.Session.FORBIDDEN_DISPLAY_NAME_PATTERN
                .matcher(arguments)
                .find()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        String displayName = arguments.strip();
        if (displayName.isEmpty()
                || displayName.getBytes(StandardCharsets.UTF_8).length > ClawConstants.Session.MAX_DISPLAY_NAME_BYTES) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return displayName;
    }
}
