/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate;

import java.util.Map;

/**
 * Mate 工具元数据批量查询接口返回的单个工具元数据。
 *
 * @param toolId 工具标识(满足 TOOL_ID_PATTERN,入执行路径)
 * @param description 可读描述
 * @param inputSchema 输入 JSON Schema
 * @param outputSchema 输出 JSON Schema
 * @param isConcurrencySafe 是否支持并发安全调用
 * @param permission Mate 服务声明的权限；{@code allow}、{@code ask} 和 {@code deny} 由服务端执行，客户端仅透传
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateToolMeta(
        String toolId,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean isConcurrencySafe,
        String permission) {

    /** 服务端未声明权限时使用的默认值。 */
    public static final String ALLOW = "allow";
}
