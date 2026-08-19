/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.campusclaw.codingagent.runtime.AgentAuthorizationPolicy.AgentPrincipal;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.Allowed;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentMetadata;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.Rejected;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.Rejection;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;

import org.junit.jupiter.api.Test;

class AgentBindingResolverTest {

    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(null, null);

    private final Map<String, ChildAgentMetadata> catalog = Map.of(
            "agent-2", new ChildAgentMetadata("agent-2", "1.0.0", true),
            "agent-3", new ChildAgentMetadata("agent-3", "1.0.0", true),
            "agent-4", new ChildAgentMetadata("agent-4", "1.0.0", false),
            "agent-7", new ChildAgentMetadata("agent-7", "1.0.0", true));

    private final AgentBindingResolver resolver = new AgentBindingResolver(
            agentId -> Optional.ofNullable(catalog.get(agentId)), AgentAuthorizationPolicy.PERMIT_ALL);

    @Test
    void resolveFiltersDisabledSelfAncestryAndUnknownChildren() {
        // agent-1 绑定 agent-2 与 agent-4; agent-4 的「当前」快照为禁用,
        // 建模 NOT_ENABLED 防御的陈旧缓存绑定场景
        // (CampusMate 契约保证查询时刻 bindingAgents 全为启用)
        PreparedAgentRuntime entry = agent("agent-1", List.of(binding("agent-2"), binding("agent-4")));
        assertEquals(
                List.of(new ChildAgentSummary("agent-2", "field-ops", "Field Ops", "Runs field operations", "1.0.0")),
                resolver.resolve(entry, PRINCIPAL, List.of("agent-1")));

        // agent-5 binds itself
        assertEquals(List.of(), resolver.resolve(agent("agent-5", List.of(binding("agent-5"))), PRINCIPAL, List.of()));

        // agent-7 binds agent-6 which is already active in the chain
        assertEquals(
                List.of(),
                resolver.resolve(
                        agent("agent-7", List.of(binding("agent-6"))), PRINCIPAL, List.of("agent-6", "agent-7")));

        // agent-2 binds an unresolvable agent
        assertEquals(
                List.of(),
                resolver.resolve(
                        agent("agent-2", List.of(binding("agent-99"))), PRINCIPAL, List.of("agent-1", "agent-2")));

        // duplicate binding ids collapse to one candidate
        PreparedAgentRuntime duplicated = agent("agent-2", List.of(binding("agent-3"), binding("agent-3")));
        assertEquals(
                1, resolver.resolve(duplicated, PRINCIPAL, List.of("agent-2")).size());
    }

    @Test
    void resolveOffersMiddleLayerChildOutsideAncestry() {
        PreparedAgentRuntime middle = agent("agent-2", List.of(binding("agent-3")));

        List<ChildAgentSummary> summaries = resolver.resolve(middle, PRINCIPAL, List.of("agent-1", "agent-2"));

        assertEquals(
                List.of(new ChildAgentSummary(
                        "agent-3", "reporting", "Reporting", "Writes diagnosis reports", "1.0.0")),
                summaries);
    }

    @Test
    void validateRejectsTargetOutsideDirectBindings() {
        PreparedAgentRuntime entry = agent("agent-1", List.of(binding("agent-2")));

        var verdict = resolver.validate(entry, PRINCIPAL, List.of("agent-1"), 0, "agent-3");

        assertEquals(new Rejected(Rejection.NOT_DIRECTLY_BOUND, "agent-3"), verdict);
    }

    @Test
    void validateRejectsSelfBindingAndAncestryReentry() {
        assertEquals(
                new Rejected(Rejection.SELF_BINDING, "agent-5"),
                resolver.validate(agent("agent-5", List.of(binding("agent-5"))), PRINCIPAL, List.of(), 0, "agent-5"));
        assertEquals(
                new Rejected(Rejection.IN_ANCESTRY, "agent-6"),
                resolver.validate(
                        agent("agent-7", List.of(binding("agent-6"))),
                        PRINCIPAL,
                        List.of("agent-6", "agent-7"),
                        1,
                        "agent-6"));
    }

    @Test
    void validateRejectsDepthExceededAndOutOfRangeParentDepth() {
        PreparedAgentRuntime deep = agent("agent-3", List.of(binding("agent-2")));

        assertEquals(
                new Rejected(Rejection.DEPTH_EXCEEDED, "parent depth 2"),
                resolver.validate(deep, PRINCIPAL, List.of("agent-1", "agent-2", "agent-3"), 2, "agent-2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.validate(deep, PRINCIPAL, List.of("agent-3"), 3, "agent-2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.validate(deep, PRINCIPAL, List.of("agent-3"), -1, "agent-2"));
    }

    @Test
    void validateRejectsUnknownDisabledAndUnauthorizedChildren() {
        assertEquals(
                new Rejected(Rejection.UNKNOWN_CHILD, "agent-99"),
                resolver.validate(
                        agent("agent-1", List.of(binding("agent-99"))), PRINCIPAL, List.of("agent-1"), 0, "agent-99"));
        assertEquals(
                new Rejected(Rejection.NOT_ENABLED, "agent-4"),
                resolver.validate(
                        agent("agent-1", List.of(binding("agent-4"))), PRINCIPAL, List.of("agent-1"), 0, "agent-4"));

        AgentBindingResolver guarded = new AgentBindingResolver(
                agentId -> Optional.ofNullable(catalog.get(agentId)),
                (principal, agentId) -> !"agent-2".equals(agentId));
        assertEquals(
                new Rejected(Rejection.NOT_AUTHORIZED, "agent-2"),
                guarded.validate(
                        agent("agent-1", List.of(binding("agent-2"))), PRINCIPAL, List.of("agent-1"), 0, "agent-2"));
    }

    @Test
    void validateRejectsVersionPinsThatDivergeFromChildMetadata() {
        PreparedAgentRuntime pinned = agent(
                "agent-1",
                List.of(new AgentReference("agent-2", "field-ops", "Field Ops", "Runs field operations", "2.0.0")));

        assertEquals(
                new Rejected(Rejection.VERSION_MISMATCH, "binding 2.0.0 vs child 1.0.0"),
                resolver.validate(pinned, PRINCIPAL, List.of("agent-1"), 0, "agent-2"));

        PreparedAgentRuntime unpinned = agent(
                "agent-1",
                List.of(new AgentReference("agent-2", "field-ops", "Field Ops", "Runs field operations", null)));
        assertTrue(resolver.validate(unpinned, PRINCIPAL, List.of("agent-1"), 0, "agent-2") instanceof Allowed);

        Map<String, ChildAgentMetadata> versionless = Map.of("agent-2", new ChildAgentMetadata("agent-2", null, true));
        AgentBindingResolver strict = new AgentBindingResolver(
                agentId -> Optional.ofNullable(versionless.get(agentId)), AgentAuthorizationPolicy.PERMIT_ALL);
        assertEquals(
                new Rejected(Rejection.VERSION_MISMATCH, "binding 1.0.0 vs child null"),
                strict.validate(
                        agent("agent-1", List.of(binding("agent-2"))), PRINCIPAL, List.of("agent-1"), 0, "agent-2"));
    }

    @Test
    void validateAllowsMiddleLayerDelegationWithChildVersion() {
        PreparedAgentRuntime middle = agent("agent-2", List.of(binding("agent-3")));

        var verdict = resolver.validate(middle, PRINCIPAL, List.of("agent-1", "agent-2"), 1, "agent-3");

        assertEquals(
                new Allowed(new ChildAgentSummary(
                        "agent-3", "reporting", "Reporting", "Writes diagnosis reports", "1.0.0")),
                verdict);
    }

    private static AgentReference binding(String agentId) {
        return new AgentReference(
                agentId,
                agentId.equals("agent-3") ? "reporting" : "field-ops",
                agentId.equals("agent-3") ? "Reporting" : "Field Ops",
                agentId.equals("agent-3") ? "Writes diagnosis reports" : "Runs field operations",
                "1.0.0");
    }

    private static PreparedAgentRuntime agent(String agentId, List<AgentReference> bindings) {
        AgentRuntime metadata = new AgentRuntime(
                List.of("glm-5.2"),
                List.of(),
                List.of(),
                bindings,
                List.of("Agent description"),
                "Display " + agentId,
                Boolean.TRUE,
                agentId,
                agentId,
                "System prompt",
                List.of(),
                "1");
        return new PreparedAgentRuntime(agentId, Path.of("agent", agentId), metadata, List.of());
    }
}
