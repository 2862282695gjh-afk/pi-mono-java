/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;

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
     * @param args 工具参数
     * @version [br_eCampusCore 26.0.0, 2026/08/22]
     * @since [br_eCampusCore 26.0.0]
     */
    record MateToolCall(String toolCallId, String tool, Map<String, Object> args) {}
}
