/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command.contributor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.codingagent.runtimeapi.command.builtin.BuiltinCommandContributor;
import com.campusclaw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.campusclaw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.service.command.SessionModelConfigurationService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Component;

/**
 * 贡献 Model 查询与切换定义；运行中允许查询，修改必须重新检查数据库状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ModelCommandContributor implements BuiltinCommandContributor {
    private final SessionModelConfigurationService service;

    public ModelCommandContributor(SessionModelConfigurationService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "model",
                        "Show or change the session model.",
                        CommandInputMode.OPTIONAL,
                        "provider/model",
                        List.of()),
                (session, withArguments) -> withArguments && !RuntimeSessionState.IDLE.matches(session.state())
                        ? RuntimeErrorCode.SESSION_BUSY.name()
                        : null,
                (context, arguments) -> CompletableFuture.completedFuture(
                        service.execute(context.session().id(), arguments)));
    }
}
