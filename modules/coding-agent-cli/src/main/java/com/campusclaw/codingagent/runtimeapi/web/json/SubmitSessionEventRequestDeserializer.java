/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web.json;

import java.io.IOException;
import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.event.CommittedEventType;
import com.campusclaw.codingagent.runtimeapi.vo.SessionUserEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.SubmitSessionEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserInterruptEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserMessageEventRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserToolConfirmationEventRequestVO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 严格解析 Session Events v2 单事件外壳并选择联合请求类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SubmitSessionEventRequestDeserializer extends JsonDeserializer<SubmitSessionEventRequestVO> {
    @Override
    public SubmitSessionEventRequestVO deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode input = parser.getCodec().readTree(parser);
        requireObject(input, context, SubmitSessionEventRequestVO.class, "event request must be an object");
        if (!fieldNames(input).equals(Set.of("event"))) {
            return context.reportInputMismatch(
                    SubmitSessionEventRequestVO.class, "event request must contain only event");
        }
        JsonNode event = input.get("event");
        requireObject(event, context, SubmitSessionEventRequestVO.class, "event must be an object");
        rejectExplicitNull(input, context);
        return new SubmitSessionEventRequestVO(deserializeEvent(parser, context, event));
    }

    @Override
    public SubmitSessionEventRequestVO getNullValue(DeserializationContext context) throws JsonMappingException {
        return context.reportInputMismatch(SubmitSessionEventRequestVO.class, "event request must be an object");
    }

    private static SessionUserEventRequestVO deserializeEvent(
            JsonParser parser, DeserializationContext context, JsonNode event) throws IOException {
        JsonNode type = event.get("type");
        if (type == null || !type.isTextual()) {
            return context.reportInputMismatch(SessionUserEventRequestVO.class, "user event type must be a string");
        }
        Class<? extends SessionUserEventRequestVO> target = eventType(type.textValue(), context);
        return parser.getCodec().treeToValue(event, target);
    }

    private static Class<? extends SessionUserEventRequestVO> eventType(
            String type, DeserializationContext context) throws JsonMappingException {
        if (CommittedEventType.USER_MESSAGE.value().equals(type)) {
            return UserMessageEventRequestVO.class;
        }
        if (CommittedEventType.USER_INTERRUPT.value().equals(type)) {
            return UserInterruptEventRequestVO.class;
        }
        if (CommittedEventType.USER_TOOL_CONFIRMATION.value().equals(type)) {
            return UserToolConfirmationEventRequestVO.class;
        }
        return context.reportInputMismatch(SessionUserEventRequestVO.class, "user event type is unsupported");
    }

    private static void rejectExplicitNull(JsonNode input, DeserializationContext context) throws JsonMappingException {
        if (containsExplicitNull(input)) {
            context.reportInputMismatch(SubmitSessionEventRequestVO.class, "event request must omit absent fields");
        }
    }

    private static boolean containsExplicitNull(JsonNode node) {
        if (node.isNull()) {
            return true;
        }
        if (!node.isContainerNode()) {
            return false;
        }
        var children = node.elements();
        while (children.hasNext()) {
            if (containsExplicitNull(children.next())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static void requireObject(
            JsonNode node, DeserializationContext context, Class<?> target, String message) throws JsonMappingException {
        if (node == null || !node.isObject()) {
            context.reportInputMismatch(target, message);
        }
    }
}
