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
import com.campusclaw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;

import org.springframework.stereotype.Component;

/**
 * 贡献绑定 Skill 清单命令的元数据、只读准入策略及委托窄服务的 Handler。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class SkillsCommandContributor implements BuiltinCommandContributor {
    private final BoundSkillQueryService service;

    public SkillsCommandContributor(BoundSkillQueryService service) {
        this.service = service;
    }

    @Override
    public BuiltinCommandDefinition definition() {
        return new BuiltinCommandDefinition(
                new BuiltinCommandMetadataDTO(
                        "skills", "List bound Skill names and descriptions.", CommandInputMode.NONE, null, List.of()),
                (session, withArguments) -> null,
                (context, arguments) -> CompletableFuture.completedFuture(service.query(context.session(), arguments)));
    }
}
