/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.delegation;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentSummary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ControlTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * 模型经其把子任务委派给已绑定子 Agent 的无状态控制工具。工具本身只做
 * 参数校验并回执请求；会话级的工具调用后处理器经 {@code LocalAgentDispatcher}
 * 执行实际委派，并用子 Agent 的答复替换回执。
 *
 * <p>组装方式与 {@code ActivateSkillTool} 相同：经
 * {@code SpringAgentToolSource} 与工具目录发现的无状态 Spring bean，
 * 会话级副作用拆到会话处理器。该工具只暴露给 resolver 找到至少一个可
 * 委派子绑定的受管运行时，绝不暴露给已达委派深度上限的 Agent。
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
     * 返回一个工具视图，其描述枚举当前可委派的子 Agent，使模型无需额外查询
     * 即可看到精确候选集（id、name、description、实际 version）。
     *
     * @param candidates 本会话经 resolver 校验的子摘要
     * @return 带增强描述的工具视图
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

    /** 仅替换描述的委派视图。 */
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
