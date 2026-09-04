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
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.RuntimeSessionStatusService;

import org.springframework.stereotype.Component;

/**
 * 贡献状态命令的元数据、只读准入策略及委托窄服务的 Handler。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class StatusCommandContributor implements BuiltinCommandContributor {
    private final RuntimeSessionStatusService service;

    public StatusCommandContributor(RuntimeSessionStatusService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "status",
                        "Show persisted Session state, model and Thinking.",
                        CommandInputMode.NONE,
                        null,
                        List.of()),
                (session, withArguments) -> null,
                (context, arguments) -> CompletableFuture.completedFuture(service.query(context.session(), arguments)));
    }
}
