/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider.mate;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 编码并解析需要按原字段重放的 Chat reasoning 内容。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
final class MateReasoningSignature {
    private static final Set<String> FIELDS =
            Set.of("reasoning_content", "reasoning", "reasoning_text", "reasoning_details");

    private MateReasoningSignature() {}

    static String encode(ObjectMapper mapper, String field, JsonNode value) {
        if (!FIELDS.contains(field)) {
            throw invalid();
        }
        ObjectNode signature = mapper.createObjectNode();
        signature.put("field", field);
        signature.set("value", value);
        return signature.toString();
    }

    static ReasoningField decode(ObjectMapper mapper, String signature) {
        try {
            JsonNode root = mapper.readTree(signature);
            String field = root.path("field").asText();
            JsonNode value = root.get("value");
            if (!FIELDS.contains(field) || value == null) {
                throw invalid();
            }
            if ("reasoning_details".equals(field) && !value.isArray()) {
                throw invalid();
            }
            if (!"reasoning_details".equals(field) && !value.isTextual()) {
                throw invalid();
            }
            return new ReasoningField(field, value);
        } catch (MateModelInvocationException error) {
            throw error;
        } catch (Exception error) {
            throw MateInvocationFailures.raise(
                    "mate.reasoningSignature.decode", MateInvocationErrorCode.INVALID_REASONING_SIGNATURE, error);
        }
    }

    private static MateModelInvocationException invalid() {
        return MateInvocationFailures.raise(
                "mate.reasoningSignature.validate", MateInvocationErrorCode.INVALID_REASONING_SIGNATURE);
    }

    record ReasoningField(String field, JsonNode value) {}
}
