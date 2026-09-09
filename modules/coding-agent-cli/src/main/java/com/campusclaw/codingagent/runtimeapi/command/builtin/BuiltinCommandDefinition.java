/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.builtin;

import java.util.Objects;

import com.campusclaw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandAdmissionPolicy;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandHandler;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandInputMode;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.command.BuiltinCommandMetadataDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

/**
 * 绑定固定元数据、准入策略和 Handler 的不可变 Builtin 定义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class BuiltinCommandDefinition implements CommandDefinition {
    private static final String ARGUMENTS_NOT_SUPPORTED = "COMMAND_ARGUMENTS_NOT_SUPPORTED";

    private final BuiltinCommandMetadataDTO metadata;

    private final CommandAdmissionPolicy admission;

    private final CommandHandler handler;

    public BuiltinCommandDefinition(
            BuiltinCommandMetadataDTO metadata, CommandAdmissionPolicy admission, CommandHandler handler) {
        this.metadata = Objects.requireNonNull(metadata);
        this.admission = Objects.requireNonNull(admission);
        this.handler = Objects.requireNonNull(handler);
        Objects.requireNonNull(metadata.name());
        Objects.requireNonNull(metadata.description());
        Objects.requireNonNull(metadata.inputMode());
    }

    public String name() {
        return metadata.name();
    }

    public CommandAdmissionPolicy admission() {
        return admission;
    }

    public CommandHandler handler() {
        return handler;
    }

    @Override
    public ResolvedCommandDTO describe(CommandSessionSnapshotDTO session) {
        String queryCode = admission.unavailableCode(session, false);
        String inputCode = metadata.inputMode() == CommandInputMode.NONE
                ? ARGUMENTS_NOT_SUPPORTED
                : admission.unavailableCode(session, true);
        ResolvedCommandDTO.InputDTO input = new ResolvedCommandDTO.InputDTO(
                metadata.inputMode().value(),
                inputCode == null,
                inputCode,
                false,
                metadata.placeholder(),
                metadata.suggestions());
        return new ResolvedCommandDTO(
                name(), CommandKind.BUILTIN, metadata.description(), queryCode == null, queryCode, input, null);
    }
}
