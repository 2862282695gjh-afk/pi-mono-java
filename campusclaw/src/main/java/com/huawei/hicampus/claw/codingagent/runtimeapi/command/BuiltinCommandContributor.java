/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

/**
 * 由各 Builtin 独立贡献元数据、准入与 Handler，不依赖中央名称分派。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface BuiltinCommandContributor {
    BuiltinCommandDefinition definition();
}
