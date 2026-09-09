/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web.json;

import java.io.IOException;

import com.campusclaw.codingagent.runtimeapi.vo.UserMessageContentRequestVO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 按内容块 type 严格选择 user.message 文本或文件请求类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public final class UserMessageContentRequestDeserializer extends JsonDeserializer<UserMessageContentRequestVO> {
    @Override
    public UserMessageContentRequestVO deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode input = parser.getCodec().readTree(parser);
        if (!input.isObject()) {
            return context.reportInputMismatch(UserMessageContentRequestVO.class, "message content must be an object");
        }
        JsonNode type = input.get("type");
        if (type == null || !type.isTextual()) {
            return context.reportInputMismatch(UserMessageContentRequestVO.class, "message content type is required");
        }
        if ("text".equals(type.textValue())) {
            return parser.getCodec().treeToValue(input, UserMessageContentRequestVO.TextRequestVO.class);
        }
        if ("file".equals(type.textValue())) {
            return parser.getCodec().treeToValue(input, UserMessageContentRequestVO.FileRequestVO.class);
        }
        return context.reportInputMismatch(UserMessageContentRequestVO.class, "message content type is unsupported");
    }

    @Override
    public UserMessageContentRequestVO getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(UserMessageContentRequestVO.class, "message content must be an object");
    }
}
