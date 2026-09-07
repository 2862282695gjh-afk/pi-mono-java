/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.huawei.hicampus.claw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 校验压缩命令参数并适配请求级调用能力，统一隐藏底层异常细节。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class SessionCompactionApplicationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCompactionApplicationService.class);

    private final RuntimeCompactionService runtime;

    public SessionCompactionApplicationService(RuntimeCompactionService runtime) {
        this.runtime = runtime;
    }

    public CompactionCommandInvocation openInvocation(MateCredentials credentials) {
        return new CompactionCommandInvocation(runtime, credentials);
    }

    public CompletionStage<? extends CommandResultDTO> execute(CommandExecutionContext context, String arguments) {
        if (arguments != null && !arguments.isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        try {
            return context.runtimeInvocation()
                    .orElseThrow()
                    .invoke(context.session().id(), context.locale())
                    .handle((result, error) -> {
                        if (error != null || result == null) {
                            throw translate(error);
                        }
                        return result;
                    })
                    .toCompletableFuture()
                    .minimalCompletionStage();
        } catch (RuntimeException error) {
            throw translate(error);
        }
    }

    private static RuntimeApiException translate(Throwable error) {
        Throwable failure = error;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        RuntimeErrorCode code = failure instanceof RuntimeApiException api
                ? commandErrorCode(api.errorCode())
                : RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        LOGGER.error("Runtime command failed: command=compact, errorCode={}", code.name());
        return new RuntimeApiException(code);
    }

    private static RuntimeErrorCode commandErrorCode(RuntimeErrorCode code) {
        return switch (code) {
            case SESSION_NOT_FOUND,
                    SESSION_BUSY,
                    AGENT_NOT_AVAILABLE,
                    MODEL_NOT_AVAILABLE,
                    MANAGER_UNAVAILABLE,
                    RUNTIME_CAPACITY_EXCEEDED,
                    INVALID_COMMAND_REQUEST -> code;
            case AGENT_MODEL_NOT_CONFIGURED -> RuntimeErrorCode.MODEL_NOT_AVAILABLE;
            default -> RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        };
    }
}
