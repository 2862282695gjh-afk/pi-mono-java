/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

/**
 * Single slash command definition contributed by a {@link CommandDefinitionSource};
 * wraps the session-scoped descriptor of the command.
 *
 * @param descriptor descriptor visible to callers
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public record CommandDefinition(CommandDescriptorDTO descriptor) {}
