/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将网关工具元数据中的 schema 字段归一化为结构化 Map。实测网关会把 schema 以
 * 「序列化 JSON 字符串」返回（如 {@code "{\"type\":\"object\",...}"}），部分版本则为
 * JSON 对象；本反序列化器同时兼容两种形态。字符串解析启用
 * {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}——尾部垃圾或多根 JSON 值
 * 同样按非法处理。非法字符串归一为 {@code null} 并 log.warn，不阻断整批元数据解析。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
public class SchemaMapDeserializer extends JsonDeserializer<Map<String, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMapDeserializer.class);

    /**
     * 启用 FAIL_ON_TRAILING_TOKENS：{@code readTree(String)} 默认只读第一个 JSON 值
     * 并忽略尾部内容，导致 <code>{"type":"object"}garbage</code> 或
     * <code>{} {"required":[...]}</code> 被截取为合法 Map。启用后尾部垃圾同样抛异常，
     * 由调用方按非法 schema 降级。
     */
    private static final ObjectMapper STRICT_MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
            JsonNode parsed = STRICT_MAPPER.readTree(raw);
            if (parsed != null && parsed.isObject()) {
                return MAPPER.convertValue(parsed, TYPE);
            }
        } catch (IOException error) {
            LOGGER.warn("Ignoring tool schema string that is not valid JSON: cause={}", error.getMessage());
        }
        return null;
    }
}
