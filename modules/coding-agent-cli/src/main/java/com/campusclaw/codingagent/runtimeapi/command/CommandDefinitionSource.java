/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * Source of slash command definitions resolved against a concrete session. Sources
 * are combined by {@link CompositeCommandRegistry}; implementations must not trigger
 * Agent refresh or model calls.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public interface CommandDefinitionSource {
    /**
     * Lists the command definitions this source contributes for the session.
     *
     * @param session authorized session providing the agent scope
     * @return definitions sorted by command name
     */
    List<CommandDefinition> list(RuntimeSessionDTO session);

    /**
     * Finds one command definition of this source by exact command name.
     *
     * @param session authorized session providing the agent scope
     * @param name command name without leading slash
     * @return definition when the name is registered by this source
     */
    Optional<CommandDefinition> find(RuntimeSessionDTO session, String name);
}
