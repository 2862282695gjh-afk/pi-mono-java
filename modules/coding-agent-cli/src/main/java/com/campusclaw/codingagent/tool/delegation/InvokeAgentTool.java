/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.delegation;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;
import com.campusclaw.codingagent.tool.catalog.ControlTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Stateless control tool through which the model delegates a subtask to a
 * bound child Agent. The tool only validates parameters and acknowledges the
 * request; the session-level after-tool-call handler performs the actual
 * delegation through {@code LocalAgentDispatcher} and replaces the
 * acknowledgement with the child Agent's answer.
 *
 * <p>Follows the same assembly pattern as {@code ActivateSkillTool}: a
 * stateless Spring bean discovered via {@code SpringAgentToolSource} and the
 * tool catalog, with the session-scoped side effect split into the session
 * handler. The tool is exposed only to managed runtimes whose resolver finds
 * at least one delegatable child binding, and never to agents already at the
 * delegation depth cap.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class InvokeAgentTool implements ControlTool {

    public static final String NAME = "invoke_agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_DESCRIPTION =
            "Delegate a self-contained subtask to a bound child Agent and wait for its answer. "
                    + "Use this for work a specialized child Agent should handle end to end; "
                    + "the child's answer is returned as this tool's result.";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "Invoke Agent";
    }

    @Override
    public String description() {
        return BASE_DESCRIPTION;
    }

    @Override
    public JsonNode parameters() {
        var properties = MAPPER.createObjectNode();
        properties.set(
                "agentId",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Exact agentId of a child Agent listed in the candidates"));
        properties.set(
                "task",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put(
                                "description",
                                "Complete, self-contained instructions for the child Agent, including all "
                                        + "context it needs because it cannot see this conversation"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", properties)
                .set("required", MAPPER.createArrayNode().add("agentId").add("task"));
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate) {
        Object agentId = params.get("agentId");
        Object task = params.get("task");
        if (!(agentId instanceof String targetId) || targetId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (!(task instanceof String taskText) || taskText.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        return new AgentToolResult(List.of(new TextContent("Agent delegation requested: " + targetId)), null);
    }

    /**
     * Returns a view of this tool whose description enumerates the currently
     * delegatable child Agents, so the model sees the exact candidate set
     * (id, name, description, actual version) without any extra lookup.
     *
     * @param candidates resolver-approved child summaries for this session
     * @return tool view with the enriched description
     */
    public ControlTool describedWith(List<ChildAgentSummary> candidates) {
        return new CandidateDescribedTool(this, renderCandidateDescription(candidates));
    }

    private static String renderCandidateDescription(List<ChildAgentSummary> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append(BASE_DESCRIPTION);
        sb.append(" Candidate child Agents (agentId — name: description");
        sb.append(candidates.stream().anyMatch(summary -> summary.version() != null) ? ", version):\n" : "):\n");
        for (ChildAgentSummary candidate : candidates) {
            sb.append("- ").append(candidate.agentId());
            if (candidate.name() != null && !candidate.name().isBlank()) {
                sb.append(" — ").append(candidate.name());
            }
            sb.append(": ");
            sb.append(candidate.description() == null ? "no description" : candidate.description());
            if (candidate.version() != null) {
                sb.append(" (version ").append(candidate.version()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Delegating view that only replaces the description. */
    private record CandidateDescribedTool(AgentTool delegate, String candidateDescription) implements ControlTool {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public String description() {
            return candidateDescription;
        }

        @Override
        public JsonNode parameters() {
            return delegate.parameters();
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate)
                throws Exception {
            return delegate.execute(toolCallId, params, signal, onUpdate);
        }
    }
}
