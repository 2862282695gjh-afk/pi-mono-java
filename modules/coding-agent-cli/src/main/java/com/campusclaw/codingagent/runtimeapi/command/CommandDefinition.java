/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

/**
 * Single slash command definition contributed by a {@link CommandDefinitionSource}.
 * Carries the stable identity used for deduplication and lookup, plus the
 * session-scoped descriptor projection for display. Admission policy and handler
 * composition arrive with the command execution layer; the mutable descriptor DTO
 * is never used as a lookup key.
 *
 * @param name stable command name without leading slash
 * @param kind stable command kind
 * @param descriptor session-scoped descriptor projection for display
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public record CommandDefinition(String name, CommandKind kind, CommandDescriptorDTO descriptor) {}
