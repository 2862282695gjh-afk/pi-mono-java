/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 由运行时注入、仅作用于一次父到子委派的可信上下文，遵循
 * {@code mainagent-subagent-design.md} §5.4。身份、权限、父缘、祖先链、
 * 深度、截止时间与有效工具集均由运行时控制，LLM 无法覆盖。
 *
 * <p>canonical constructor 强制以下结构不变量，使非法委派状态无法被构造：
 *
 * <ul>
 *   <li>{@code delegationDepth} 保持在 {@code 1..MAX_DELEGATION_DEPTH} 内；</li>
 *   <li>{@code ancestryAgentIds} 不可变、无重复，且长度恰等于委派深度，
 *       以 {@code parentAgentId} 结尾；</li>
 *   <li>{@code targetAgentId} 绝不出现在祖先链中——由于父 Agent 已在链上
 *       收尾，该约束同时排除了自绑定。</li>
 * </ul>
 *
 * <p>{@code tenantId}/{@code userId} 在本地 CLI 运行中可为 {@code null}。
 * 边级生命周期标识（{@code parentAgentSessionId}、{@code parentRunId}、
 * {@code subTaskId}、{@code idempotencyKey}、{@code deadline}）在调度器与
 * SubTask 生命周期接入前为 {@code null}；上述结构保证届时已经成立。
 *
 * @param tenantId             发起调用的租户，本地 CLI 运行为 {@code null}
 * @param userId               发起调用的用户，本地 CLI 运行为 {@code null}
 * @param conversationId       整条链所服务的会话
 * @param parentAgentSessionId 发起委派的父 Agent 会话标识
 * @param parentRunId          发起委派的父 Agent 运行标识
 * @param subTaskId            本次委派执行的 SubTask
 * @param invocationId         本委派边的唯一标识
 * @param parentAgentId        发起委派的父 Agent id
 * @param targetAgentId        被委派的子 Agent id
 * @param ancestryAgentIds     已激活的 Agent id 列表，入口在前、父 Agent 在末尾
 * @param delegationDepth      目标所处深度，首次委派为 1
 * @param idempotencyKey       本委派边的幂等键
 * @param deadline             本委派边的截止时间
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record DelegationContext(
        String tenantId,
        String userId,
        String conversationId,
        String parentAgentSessionId,
        String parentRunId,
        String subTaskId,
        String invocationId,
        String parentAgentId,
        String targetAgentId,
        List<String> ancestryAgentIds,
        int delegationDepth,
        String idempotencyKey,
        Instant deadline) {

    /** 硬性委派深度上限：入口深度 0，首次委派 1，二次委派 2。 */
    public static final int MAX_DELEGATION_DEPTH = 2;

    public DelegationContext {
        ancestryAgentIds = ancestryAgentIds == null ? List.of() : List.copyOf(ancestryAgentIds);
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(invocationId, "invocationId");
        requireNonBlank(parentAgentId, "parentAgentId");
        requireNonBlank(targetAgentId, "targetAgentId");
        if (delegationDepth < 1 || delegationDepth > MAX_DELEGATION_DEPTH) {
            throw new IllegalArgumentException("Delegation depth out of range: " + delegationDepth);
        }
        if (ancestryAgentIds.size() != delegationDepth) {
            throw new IllegalArgumentException(
                    "Ancestry length must equal delegation depth: " + ancestryAgentIds.size());
        }
        if (ancestryAgentIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Ancestry must not contain null agent ids");
        }
        if (new HashSet<>(ancestryAgentIds).size() != ancestryAgentIds.size()) {
            throw new IllegalArgumentException("Ancestry must not contain duplicate agent ids");
        }
        if (!parentAgentId.equals(ancestryAgentIds.getLast())) {
            throw new IllegalArgumentException("Ancestry must end with the parent agent id");
        }
        if (ancestryAgentIds.contains(targetAgentId)) {
            throw new IllegalArgumentException("Target agent must not appear in the ancestry");
        }
    }

    /**
     * 构造入口 Agent 发起首次委派的上下文。
     *
     * @param entryAgentId   入口 Agent id，按定义深度为 0
     * @param targetAgentId  被委派的子 Agent id
     * @param conversationId 本条链所服务的会话
     * @param invocationId   本委派边的唯一标识
     * @return 祖先链为 {@code [entryAgentId]}、深度为 1 的上下文
     */
    public static DelegationContext forEntry(
            String entryAgentId, String targetAgentId, String conversationId, String invocationId) {
        return new DelegationContext(
                null,
                null,
                conversationId,
                null,
                null,
                null,
                invocationId,
                entryAgentId,
                targetAgentId,
                List.of(entryAgentId),
                1,
                null,
                null);
    }

    /**
     * 构造由本上下文所描述的 Agent 再次委派时的下一跳上下文。身份字段原样传递；
     * 边级生命周期标识重置为 {@code null}，由调度器填充。
     *
     * @param nextTargetAgentId 被委派的子 Agent id
     * @param nextInvocationId  新委派边的唯一标识
     * @return 祖先链延长、深度加一的上下文
     * @throws IllegalStateException 已达硬性深度上限时抛出
     */
    public DelegationContext delegateTo(String nextTargetAgentId, String nextInvocationId) {
        if (!canDelegateFurther()) {
            throw new IllegalStateException("Delegation depth limit reached: " + delegationDepth);
        }
        List<String> extended = new ArrayList<>(ancestryAgentIds);
        extended.add(targetAgentId);
        return new DelegationContext(
                tenantId,
                userId,
                conversationId,
                null,
                null,
                null,
                nextInvocationId,
                targetAgentId,
                nextTargetAgentId,
                extended,
                delegationDepth + 1,
                null,
                null);
    }

    /**
     * 判定本上下文描述的 Agent 能否继续委派。
     *
     * @return 再委派一次仍在深度上限内时为 {@code true}
     */
    public boolean canDelegateFurther() {
        return delegationDepth < MAX_DELEGATION_DEPTH;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
