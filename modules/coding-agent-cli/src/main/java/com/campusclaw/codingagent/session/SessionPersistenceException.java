/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

/**
 * Thrown when session persistence operations (save/load) fail.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class SessionPersistenceException extends RuntimeException {

    public SessionPersistenceException(String message) {
        super(message);
    }

    public SessionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
