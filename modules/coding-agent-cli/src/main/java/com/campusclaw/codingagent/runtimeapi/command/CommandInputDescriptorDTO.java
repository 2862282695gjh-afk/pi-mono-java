/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;

import lombok.Data;

/**
 * Argument input descriptor of a slash command, describing whether arguments and
 * attachments are accepted in the current session state.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class CommandInputDescriptorDTO {
    private CommandInputMode mode;
    private boolean available;
    private String unavailableCode;
    private boolean acceptsFiles;
    private String placeholder;
    private List<String> suggestions;
}
