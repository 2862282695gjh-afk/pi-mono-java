/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;

/**
 * 按调用解析 Mate 工具凭据的提供者。部署方注册本接口的 Spring Bean 即可
 * 把运行时上下文（如 Loop 下发的 Authorization、请求头、会话）接入
 * {@code callMateTool}；每次工具调用都会携带 {@link MateToolCall} 上下文
 * 重新解析，实现按调用隔离——并发会话各自凭据互不串用。
 *
 * <p>上下文包含 {@code AgentTool.execute} 能提供的全部调用标识
 * （toolCallId / tool / args）。会话级身份（AgentContext、会话 ID）当前
 * 不在 AgentTool 契约内，部署方如需会话粒度可经请求作用域机制补足，
 * 见 {@code docs/designs/mate-tool-client.md} 的边界说明。
 *
 * <p>未注册时的行为：{@code callMateTool} 以 fail-closed 方式拒绝执行
 * （详见 {@code HttpMateToolClient.invokeTool} 的凭据校验），不会发出
 * 未认证请求。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
public interface MateCredentialResolver {

    /**
     * 解析指定工具调用要透传的凭据。
     *
     * @param call 本次调用的上下文（toolCallId / tool / args）
     * @return 完整凭据（需满足 {@link MateCredentials#isComplete()}）；返回
     *         null 或残缺凭据时该次调用被拒绝
     */
    MateCredentials resolve(MateToolCall call);

    /**
     * 单次 Mate 工具调用的上下文快照：{@code AgentTool.execute} 签名能
     * 提供的全部标识。作为 record 传递不可变快照，resolver 据此区分并发
     * 调用而无需依赖外部可变状态。
     *
     * @param toolCallId 本次工具调用的唯一标识
     * @param tool 待调用的工具标识
     * @param args 工具参数；构造时经 JSON 往返深拷贝——嵌套 Map/List 与
     *        出站请求不再共享对象，且全层不可变
     * @version [br_eCampusCore 26.0.0, 2026/08/22]
     * @since [br_eCampusCore 26.0.0]
     */
    record MateToolCall(String toolCallId, String tool, Map<String, Object> args) {
        private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        /** 经 JSON 往返深拷贝 args 并逐层只读包装，保证快照全层不可变。 */
        public MateToolCall {
            args = deepCopyArgs(args);
        }

        private static Map<String, Object> deepCopyArgs(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            try {
                Map<String, Object> copy = SNAPSHOT_MAPPER.readValue(
                        SNAPSHOT_MAPPER.writeValueAsString(source),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                return readOnly(copy);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("tool args are not JSON-serializable", e);
            }
        }

        private static Map<String, Object> readOnly(Map<String, Object> map) {
            java.util.Map<String, Object> wrapped = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> wrapped.put(key, readOnlyValue(value)));
            return java.util.Collections.unmodifiableMap(wrapped);
        }

        private static Object readOnlyValue(Object value) {
            if (value instanceof Map<?, ?> nested) {
                java.util.Map<String, Object> wrapped = new java.util.LinkedHashMap<>();
                nested.forEach((key, inner) -> wrapped.put(String.valueOf(key), readOnlyValue(inner)));
                return java.util.Collections.unmodifiableMap(wrapped);
            }
            if (value instanceof List<?> list) {
                java.util.List<Object> wrapped = new java.util.ArrayList<>();
                list.forEach(item -> wrapped.add(readOnlyValue(item)));
                return java.util.Collections.unmodifiableList(wrapped);
            }
            return value;
        }
    }
}
