/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

/**
 * Signals that a managed Agent runtime could not be fetched, materialized, or activated.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/10]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class AgentRuntimeException extends IllegalStateException {

    public AgentRuntimeException(String message) {
        super(message);
    }

    public AgentRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
