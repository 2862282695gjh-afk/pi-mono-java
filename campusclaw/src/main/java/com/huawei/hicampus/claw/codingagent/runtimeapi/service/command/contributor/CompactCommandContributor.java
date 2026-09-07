/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.SessionCompactionApplicationService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeSessionState;

import org.springframework.stereotype.Component;

/**
 * 贡献仅允许 idle 空参调用的压缩定义，最终状态由 Runtime 在共享锁内复核。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class CompactCommandContributor implements BuiltinCommandContributor {
    private final SessionCompactionApplicationService service;

    public CompactCommandContributor(SessionCompactionApplicationService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "compact", "Compact session context.", CommandInputMode.NONE, null, List.of()),
                (session, withArguments) ->
                        RuntimeSessionState.IDLE.matches(session.state()) ? null : RuntimeErrorCode.SESSION_BUSY.name(),
                service::execute);
    }
}
