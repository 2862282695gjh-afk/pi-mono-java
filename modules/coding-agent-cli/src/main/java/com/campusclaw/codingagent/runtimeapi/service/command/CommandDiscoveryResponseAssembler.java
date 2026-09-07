/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import java.util.Comparator;

import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;
import com.campusclaw.codingagent.runtimeapi.vo.CommandListResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.CommandListResponseVO.DescriptorResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.CommandListResponseVO.InputResponseVO;
import com.campusclaw.common.constant.ClawConstants;

import org.springframework.stereotype.Component;

/**
 * 将一个已解析 Catalog 投影为轻量清单；完整缓存检查与执行授权由应用层负责。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class CommandDiscoveryResponseAssembler {
    public CommandListResponseVO assemble(ResolvedCommandCatalog catalog) {
        for (ResolvedCommandDTO command : catalog.list()) {
            displayOrder(command);
        }
        return new CommandListResponseVO(catalog.list().stream()
                .filter(ResolvedCommandDTO::available)
                .sorted(Comparator.comparingInt(this::displayOrder).thenComparing(ResolvedCommandDTO::name))
                .map(this::descriptor)
                .toList());
    }

    private int displayOrder(ResolvedCommandDTO command) {
        if (command.kind() == CommandKind.SKILL) {
            return ClawConstants.RuntimeApi.Command.BUILTIN_ORDER.size();
        }
        int order = ClawConstants.RuntimeApi.Command.BUILTIN_ORDER.indexOf(command.name());
        if (order < 0) {
            throw new IllegalStateException("Unrecognized Builtin display order: " + command.name());
        }
        return order;
    }

    private DescriptorResponseVO descriptor(ResolvedCommandDTO command) {
        return new DescriptorResponseVO(command.name(), command.kind().value(), command.description(), input(command));
    }

    private InputResponseVO input(ResolvedCommandDTO command) {
        if (command.input() == null || !command.input().available()) {
            return null;
        }
        if (command.kind() == CommandKind.SKILL) {
            return new InputResponseVO(ClawConstants.RuntimeApi.Command.SKILL_INPUT_HINT, true);
        }
        String hint = ClawConstants.RuntimeApi.Command.BUILTIN_INPUT_HINTS.get(command.name());
        return hint == null ? null : new InputResponseVO(hint, false);
    }
}
