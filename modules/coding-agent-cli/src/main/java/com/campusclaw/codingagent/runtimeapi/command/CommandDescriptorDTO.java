/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import lombok.Data;

/**
 * Slash command descriptor returned by the command list endpoint. Availability is
 * evaluated for the queried session state; temporarily unavailable commands stay
 * listed together with a stable unavailable reason code.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class CommandDescriptorDTO {
    private String name;
    private CommandKind kind;
    private String description;
    private boolean available;
    private String unavailableCode;
    private CommandInputDescriptorDTO input;
}
