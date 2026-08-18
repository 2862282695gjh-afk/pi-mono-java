/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;

/**
 * 按 {@code mainagent-subagent-design.md} §2.3 与 §5.1 计算一个父 Agent 的有效子 Agent 集合：
 *
 * <pre>
 * effectiveChildAgents = parentAgent.bindingAgents
 *         ∩ enabledAgents
 *         ∩ principalAuthorizedAgents
 *         - ancestryAgents
 * </pre>
 *
 * <p>候选只来自父 Agent 本地快照中的绑定记录，父 Agent 绝不读取全局 Agent 目录。
 * {@link #resolve} 生成 {@code invoke_agent} 工具描述所需的轻量摘要；
 * {@link #validate} 在委派执行前复查全部规则。resolver 绝不信任提示词内容，
 * 未知子元数据一律 fail closed。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class AgentBindingResolver {

    private final ChildAgentMetadataSource metadataSource;
    private final AgentAuthorizationPolicy authorizationPolicy;

    /**
     * 基于子元数据源与鉴权策略构造 resolver。
     *
     * @param metadataSource      加载子 Agent 元数据，本地优先
     * @param authorizationPolicy 判定主体对目标 Agent 的访问权限
     */
    public AgentBindingResolver(ChildAgentMetadataSource metadataSource, AgentAuthorizationPolicy authorizationPolicy) {
        this.metadataSource = Objects.requireNonNull(metadataSource);
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy);
    }

    /**
     * 返回当前调用链下向父 Agent 提供的子 Agent 列表。
     * 空白、重复、自引用、已在链上激活、未知、禁用、版本不兼容或未授权的绑定
     * 被静默过滤；需要带原因的裁决请用 {@link #validate}。
     *
     * @param parent          发起委派的父 Agent 运行时
     * @param principal       发起调用的用户身份
     * @param invocationChain 已激活的 Agent id 列表（含父 Agent 自身）
     * @return 供 {@code invoke_agent} 描述使用的不可变摘要列表
     */
    public List<ChildAgentSummary> resolve(
            PreparedAgentRuntime parent, AgentPrincipal principal, List<String> invocationChain) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(principal);
        List<String> chain = invocationChain == null ? List.of() : List.copyOf(invocationChain);
        List<ChildAgentSummary> summaries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AgentReference binding : parent.bindingAgents()) {
            String childId = binding.id();
            if (childId == null || childId.isBlank() || !seen.add(childId)) {
                continue;
            }
            if (parent.agentId().equals(childId) || chain.contains(childId)) {
                continue;
            }
            Verdict candidate = verdict(parent, principal, chain, childId);
            if (candidate instanceof Allowed allowed) {
                summaries.add(allowed.child());
            }
        }
        return List.copyOf(summaries);
    }

    /**
     * 执行前复查单次委派，在 {@link #resolve} 逐候选规则之上叠加深度上限。
     *
     * @param parent          发起委派的父 Agent 运行时
     * @param principal       发起调用的用户身份
     * @param invocationChain 已激活的 Agent id 列表（含父 Agent 自身）
     * @param parentDepth     父 Agent 所处深度，入口 Agent 为 0
     * @param targetAgentId   请求委派的目标子 Agent id
     * @return 放行摘要或带原因的拒绝
     * @throws IllegalArgumentException 当 {@code parentDepth} 超出
     *         {@code 0..MAX_DELEGATION_DEPTH} 范围
     */
    public Verdict validate(
            PreparedAgentRuntime parent,
            AgentPrincipal principal,
            List<String> invocationChain,
            int parentDepth,
            String targetAgentId) {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(principal);
        Objects.requireNonNull(targetAgentId);
        if (parentDepth < 0 || parentDepth > DelegationContext.MAX_DELEGATION_DEPTH) {
            throw new IllegalArgumentException("Parent depth out of range: " + parentDepth);
        }
        if (parentDepth + 1 > DelegationContext.MAX_DELEGATION_DEPTH) {
            return new Rejected(Rejection.DEPTH_EXCEEDED, "parent depth " + parentDepth);
        }
        List<String> chain = invocationChain == null ? List.of() : List.copyOf(invocationChain);
        return verdict(parent, principal, chain, targetAgentId);
    }

    private Verdict verdict(
            PreparedAgentRuntime parent, AgentPrincipal principal, List<String> chain, String targetAgentId) {
        Optional<AgentReference> bound = parent.bindingAgents().stream()
                .filter(binding -> targetAgentId.equals(binding.id()))
                .findFirst();
        if (bound.isEmpty()) {
            return new Rejected(Rejection.NOT_DIRECTLY_BOUND, targetAgentId);
        }
        if (parent.agentId().equals(targetAgentId)) {
            return new Rejected(Rejection.SELF_BINDING, targetAgentId);
        }
        if (chain.contains(targetAgentId)) {
            return new Rejected(Rejection.IN_ANCESTRY, targetAgentId);
        }
        Optional<ChildAgentMetadata> metadata = metadataSource.load(targetAgentId);
        if (metadata.isEmpty()) {
            return new Rejected(Rejection.UNKNOWN_CHILD, targetAgentId);
        }
        ChildAgentMetadata child = metadata.get();

        // CampusMate 契约保证 bindingAgents 只引用查询时刻处于启用状态的子 Agent；
        // 此处复查的是子 Agent 的「当前」状态，防御父快照缓存后子才被禁用的陈旧绑定。
        if (!child.enabled()) {
            return new Rejected(Rejection.NOT_ENABLED, targetAgentId);
        }
        if (versionIncompatible(bound.get(), child)) {
            return new Rejected(
                    Rejection.VERSION_MISMATCH, "binding " + bound.get().version() + " vs child " + child.version());
        }
        if (!authorizationPolicy.isAuthorized(principal, targetAgentId)) {
            return new Rejected(Rejection.NOT_AUTHORIZED, targetAgentId);
        }
        return new Allowed(new ChildAgentSummary(
                child.agentId(),
                bound.get().name(),
                bound.get().displayName(),
                bound.get().description(),
                child.version()));
    }

    private static boolean versionIncompatible(AgentReference binding, ChildAgentMetadata child) {
        String pinned = binding.version();
        if (pinned == null || pinned.isBlank()) {
            return false;
        }
        return child.version() == null || !pinned.equals(child.version());
    }

    /**
     * 嵌入 {@code invoke_agent} 工具描述的轻量子 Agent 摘要。version 取子 Agent
     * 的实际元数据，而非父 Agent 绑定时固定的版本。
     *
     * @param agentId     子 Agent id
     * @param name        绑定名称
     * @param displayName 绑定显示名
     * @param description 展示给模型的绑定描述
     * @param version     子 Agent 的实际版本
     */
    record ChildAgentSummary(String agentId, String name, String displayName, String description, String version) {}

    /** resolver 判定一个子 Agent 所需的元数据。 */
    record ChildAgentMetadata(String agentId, String version, boolean enabled) {}

    /** 加载子 Agent 元数据，不物化完整运行时。 */
    @FunctionalInterface
    interface ChildAgentMetadataSource {

        /**
         * 加载一个子 Agent 的元数据。
         *
         * @param agentId 子 Agent id
         * @return 元数据；子 Agent 无法解析时为空
         */
        Optional<ChildAgentMetadata> load(String agentId);
    }

    /** 单次委派校验的裁决结果。 */
    sealed interface Verdict permits Allowed, Rejected {}

    /**
     * 委派通过全部规则。
     *
     * @param child 通过校验的子 Agent 摘要
     */
    record Allowed(ChildAgentSummary child) implements Verdict {}

    /**
     * 委派违反某条规则。
     *
     * @param reason 违反的规则
     * @param detail 英文诊断明细
     */
    record Rejected(Rejection reason, String detail) implements Verdict {}

    /** 委派可能违反的规则。 */
    enum Rejection {
        NOT_DIRECTLY_BOUND,
        SELF_BINDING,
        IN_ANCESTRY,
        DEPTH_EXCEEDED,
        UNKNOWN_CHILD,
        NOT_ENABLED,
        VERSION_MISMATCH,
        NOT_AUTHORIZED
    }
}
