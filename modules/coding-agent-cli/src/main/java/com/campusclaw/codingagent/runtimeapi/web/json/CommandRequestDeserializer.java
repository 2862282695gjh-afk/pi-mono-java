/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web.json;

import java.io.IOException;

import com.campusclaw.codingagent.runtimeapi.vo.BuiltinCommandRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.CommandRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.SkillCommandRequestVO;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 仅按名称命名空间选择请求类型，字段格式由所选 VO 的 Jakarta 约束校验。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public final class CommandRequestDeserializer extends JsonDeserializer<CommandRequestVO> {
    @Override
    public CommandRequestVO deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode input = parser.getCodec().readTree(parser);
        if (!input.isObject()) {
            return context.reportInputMismatch(CommandRequestVO.class, "command request must be an object");
        }
        JsonNode name = input.get("name");
        if (name != null && name.isTextual() && name.textValue().startsWith(ClawConstants.Skill.COMMAND_PREFIX)) {
            return parser.getCodec().treeToValue(input, SkillCommandRequestVO.class);
        }
        return parser.getCodec().treeToValue(input, BuiltinCommandRequestVO.class);
    }

    @Override
    public CommandRequestVO getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(CommandRequestVO.class, "command request must be an object");
    }
}
