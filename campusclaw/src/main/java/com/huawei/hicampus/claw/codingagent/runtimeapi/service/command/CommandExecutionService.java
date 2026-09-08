/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.BuiltinCommandRequestVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.validation.Validator;

/**
 * 协调已选定 Builtin 请求的校验、单次解析、准入、执行及业务响应投影。
 * 不承担共享 HTTP 的类别识别，也不将 Builtin 约束用于 Skill 请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CommandExecutionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandExecutionService.class);

    private final RuntimeSessionRepository repository;

    private final CompositeCommandRegistry registry;

    private final SessionCompactionApplicationService compaction;

    private final CommandResponseAssembler responses;

    private final Validator validator;

    public CommandExecutionService(
            RuntimeSessionRepository repository,
            CompositeCommandRegistry registry,
            SessionCompactionApplicationService compaction,
            CommandResponseAssembler responses,
            Validator validator) {
        this.repository = repository;
        this.registry = registry;
        this.compaction = compaction;
        this.responses = responses;
        this.validator = validator;
    }

    /**
     * 执行已选定 Builtin 类别的请求；共享入口仍须单独完成类别识别和 Header 处理。
     *
     * @param sessionId 已由 API 边界校验的 Session 标识
     * @param request Builtin 请求，不修改调用方对象
     * @param locale 本次语言
     * @param credentials 本次透传凭据，不解析、记录或存储
     * @return 业务 VO 与可选 ETag 的隔离完成视图，不是公开回执包装
     */
    public CompletionStage<RuntimeSessionView<?>> executeBuiltin(
            String sessionId, BuiltinCommandRequestVO request, Locale locale, MateCredentials credentials) {
        try {
            var input = normalize(request);
            var session = repository
                    .find(sessionId)
                    .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_FOUND));
            var catalog = registry.resolve(session, CommandKind.BUILTIN);
            var definition = select(catalog, input.getName());
            requireAdmission(definition, catalog, input.getArguments());
            try (var invocation = compaction.openInvocation(credentials)) {
                var context = new CommandExecutionContext(locale, catalog, invocation);
                return definition
                        .handler()
                        .execute(context, input.getArguments())
                        .handle(this::assemble)
                        .toCompletableFuture()
                        .minimalCompletionStage();
            }
        } catch (RuntimeException error) {
            return CompletableFuture.failedStage(translate(error));
        }
    }

    /**
     * 在请求线程等待隔离完成视图；不向已接受的执行传播客户端取消。
     *
     * @param sessionId Session 标识
     * @param request Builtin 请求
     * @param locale 本次语言
     * @param credentials 本次透传凭据
     * @return 业务 VO 与对应 ETag
     */
    public RuntimeSessionView<?> executeBuiltinAndAwait(
            String sessionId, BuiltinCommandRequestVO request, Locale locale, MateCredentials credentials) {
        try {
            return executeBuiltin(sessionId, request, locale, credentials)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException error) {
            throw translate(error);
        }
    }

    private BuiltinCommandRequestVO normalize(BuiltinCommandRequestVO request) {
        if (request == null) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        var input = BuiltinCommandRequestVO.builder()
                .name(request.getName())
                .arguments(Objects.requireNonNullElse(request.getArguments(), ""))
                .build();
        if (!validator.validate(input).isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return input;
    }

    private BuiltinCommandDefinition select(ResolvedCommandCatalog catalog, String name) {
        var definition = catalog.findDefinition(name)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.COMMAND_NOT_FOUND));
        if (!(definition instanceof BuiltinCommandDefinition builtin)) {
            throw new RuntimeApiException(RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
        }
        return builtin;
    }

    private void requireAdmission(
            BuiltinCommandDefinition definition, ResolvedCommandCatalog catalog, String arguments) {
        String code = definition.admission().unavailableCode(catalog.session(), !arguments.isEmpty());
        if (code != null) {
            throw new RuntimeApiException(RuntimeErrorCode.valueOf(code));
        }
    }

    private RuntimeSessionView<?> assemble(CommandResultDTO result, Throwable error) {
        if (error != null) {
            throw translate(error);
        }
        try {
            return responses.assemble(result);
        } catch (RuntimeException failure) {
            throw translate(failure);
        }
    }

    private static RuntimeApiException translate(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        RuntimeErrorCode code = cause instanceof RuntimeApiException api
                ? commandError(api.errorCode())
                : RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        LOGGER.error("Builtin command failed: errorCode={}", code.name());
        return new RuntimeApiException(code);
    }

    private static RuntimeErrorCode commandError(RuntimeErrorCode code) {
        return switch (code) {
            case INVALID_COMMAND_REQUEST,
                    COMMAND_NOT_FOUND,
                    SESSION_NOT_FOUND,
                    SESSION_BUSY,
                    AGENT_NOT_AVAILABLE,
                    MODEL_NOT_AVAILABLE,
                    THINKING_NOT_SUPPORTED,
                    SESSION_NAME_UPDATE_FAILED,
                    COMMAND_EXECUTION_FAILED,
                    MANAGER_UNAVAILABLE,
                    RUNTIME_CAPACITY_EXCEEDED -> code;
            case AGENT_MODEL_NOT_CONFIGURED -> RuntimeErrorCode.MODEL_NOT_AVAILABLE;
            default -> RuntimeErrorCode.COMMAND_EXECUTION_FAILED;
        };
    }
}
