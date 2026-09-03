/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Command kind of a slash command descriptor.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CommandKind {
    BUILTIN("builtin"),
    SKILL("skill");

    private final String value;

    CommandKind(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase wire value used in descriptors and HTTP payloads.
     *
     * @return wire value
     */
    @JsonValue
    public String value() {
        return value;
    }
}
