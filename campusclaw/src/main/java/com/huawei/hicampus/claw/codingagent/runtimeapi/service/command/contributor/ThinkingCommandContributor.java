/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionThinkingConfigurationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Component;

/**
 * 贡献 Thinking 查询与开关定义；准入不触发 Agent 或模型查询。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ThinkingCommandContributor implements BuiltinCommandContributor {
    private final SessionThinkingConfigurationService service;

    public ThinkingCommandContributor(SessionThinkingConfigurationService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "thinking",
                        "Show or change session thinking.",
                        CommandInputMode.OPTIONAL,
                        "on|off",
                        List.of("on", "off")),
                (session, withArguments) -> withArguments && !RuntimeSessionState.IDLE.matches(session.state())
                        ? RuntimeErrorCode.SESSION_BUSY.name()
                        : null,
                (context, arguments) -> CompletableFuture.completedFuture(
                        service.execute(context.session().id(), arguments)));
    }
}
