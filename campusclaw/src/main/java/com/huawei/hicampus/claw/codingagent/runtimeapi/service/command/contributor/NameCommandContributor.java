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
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionNamingService;

import org.springframework.stereotype.Component;

/**
 * 贡献 Name 查询和修改定义；idle 与 running 均允许修改显示名称。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class NameCommandContributor implements BuiltinCommandContributor {
    private final SessionNamingService service;

    public NameCommandContributor(SessionNamingService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "name",
                        "Show or change the session name.",
                        CommandInputMode.OPTIONAL,
                        "session name",
                        List.of()),
                (session, withArguments) -> null,
                (context, arguments) -> CompletableFuture.completedFuture(
                        service.execute(context.session().id(), arguments)));
    }
}
