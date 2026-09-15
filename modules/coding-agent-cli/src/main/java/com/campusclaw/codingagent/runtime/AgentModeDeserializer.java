/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.io.IOException;

import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * 保留 Agent mode 的严格字符串边界，并区分缺省字段与显式 null。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class AgentModeDeserializer extends JsonDeserializer<String> {
    static final String INVALID_NULL = "\u0000";

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return parser.hasToken(JsonToken.VALUE_STRING)
                ? parser.getText()
                : (String) context.handleUnexpectedToken(String.class, parser);
    }

    @Override
    public String getNullValue(DeserializationContext context) {
        return INVALID_NULL;
    }

    @Override
    public String getAbsentValue(DeserializationContext context) {
        return ClawConstants.Agent.MODE_BUILD;
    }
}
