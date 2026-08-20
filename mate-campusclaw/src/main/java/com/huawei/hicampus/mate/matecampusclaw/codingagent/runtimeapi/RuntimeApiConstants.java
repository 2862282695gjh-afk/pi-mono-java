/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi;

/**
 * Runtime HTTP V1 的公共路径和标识符约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeApiConstants {
    public static final String BASE_PATH = "/campusclaw-service/v1";

    public static final String AGENT_ID_PATTERN = "^agent_[0-9A-Za-z]{24}$";

    public static final String SESSION_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$";

    public static final String MODEL_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$";

    public static final int MAX_MESSAGE_CHARACTERS = 262144;

    public static final int MAX_FILE_IDS = 32;

    private RuntimeApiConstants() {}
}
