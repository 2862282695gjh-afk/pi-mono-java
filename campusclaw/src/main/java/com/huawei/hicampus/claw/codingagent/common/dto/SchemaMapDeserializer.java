/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.common.dto;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将网关工具元数据中的 schema 字段归一化为结构化 Map。实测网关会把 schema 以
 * 「序列化 JSON 字符串」返回（如 {@code "{\"type\":\"object\",...}"}），部分版本则为
 * JSON 对象；本反序列化器同时兼容两种形态。非法 JSON 字符串按未声明 schema
 * 处理为 {@code null}，由上层按缺省语义兜底，不阻断整批元数据解析。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
public class SchemaMapDeserializer extends JsonDeserializer<Map<String, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMapDeserializer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeRef TYPE = new TypeRef();

    /**
     * 泛型常量占位，避免每次反序列化重复构造 TypeReference。
     */
    private static final class TypeRef extends com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> {}

    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return toSchemaMap(parser.getCodec().readTree(parser));
    }

    static Map<String, Object> toSchemaMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return MAPPER.convertValue(node, TYPE);
        }
        if (node.isTextual()) {
            return fromText(node.textValue());
        }
        return null;
    }

    private static Map<String, Object> fromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            if (parsed != null && parsed.isObject()) {
                return MAPPER.convertValue(parsed, TYPE);
            }
        } catch (IOException error) {
            LOGGER.warn("Ignoring tool schema string that is not valid JSON: cause={}", error.getMessage());
        }
        return null;
    }
}
