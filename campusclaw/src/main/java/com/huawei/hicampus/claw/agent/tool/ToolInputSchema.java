/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 解析框架工具以 JSON 文本声明的输入定义，不承担调用参数的 Schema 校验。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ToolInputSchema {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInputSchema() {}

    /**
     * 解析单个对象定义；工具应私有保存解析结果，并向调用方返回深拷贝。
     *
     * @param json 工具输入定义的 JSON 文本
     * @return 可用于工具私有静态字段的对象定义
     * @throws IllegalArgumentException 定义不是合法的单个 JSON 对象时抛出
     */
    public static ObjectNode parse(String json) {
        try {
            var node = MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (!(node instanceof ObjectNode schema)) {
                throw new IllegalArgumentException("Tool input schema must be a JSON object");
            }
            return schema;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tool input schema JSON", exception);
        }
    }
}
