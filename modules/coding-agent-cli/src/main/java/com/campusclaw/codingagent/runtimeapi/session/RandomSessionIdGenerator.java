/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

import java.util.UUID;

/**
 * 使用带类型前缀且去除连字符的 UUID 生成 Session 标识。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RandomSessionIdGenerator implements SessionIdGenerator {
    @Override
    public String nextId() {
        return "session-" + UUID.randomUUID().toString().replace("-", "");
    }
}
