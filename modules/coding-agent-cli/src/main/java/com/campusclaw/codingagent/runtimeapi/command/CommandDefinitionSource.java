/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * Source of resolved commands for a concrete session. Sources only list the
 * commands they discover; exact-name lookup is served by
 * {@link CompositeCommandRegistry} on a single resolved view per request.
 * Implementations must not trigger Agent refresh or model calls. The executable
 * {@code CommandDefinition} (stable metadata, admission policy and handler) is a
 * command-execution-layer composition over these discoveries.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public interface CommandDefinitionSource {
    /**
     * Lists the resolved commands this source contributes for the session.
     *
     * @param session authorized session providing the agent scope
     * @return commands sorted by command name
     */
    List<ResolvedCommand> list(RuntimeSessionDTO session);
}
