/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists tools available from the Mate tool service, filtered by agent or skill
 * authorization.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class ListMateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListMateTool.class);

    private final CallMateTool.MateToolClient client;
    private final CallMateTool callMateTool;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode PARAMETERS;

    static {
        try {
            PARAMETERS = MAPPER.readTree(
                    """
                    {"type":"object",
                     "properties":{
                       "agent_id":{"type":"string","description":"Agent ID to list authorized tools for"},
                       "skill_id":{"type":"string","description":"Skill ID to list authorized tools for"}
                     }}""");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse listMateTool schema", e);
        }
    }

    /**
     * Creates a ListMateTool.
     *
     * @param client the Mate tool service client (shared with CallMateTool)
     * @param callMateTool the CallMateTool whose meta cache will be updated
     */
    public ListMateTool(CallMateTool.MateToolClient client, CallMateTool callMateTool) {
        this.client = client;
        this.callMateTool = callMateTool;
    }

    @Override
    public String name() {
        return "listMateTool";
    }

    @Override
    public String label() {
        return "List Mate Tools";
    }

    @Override
    public String description() {
        return "List tools available from the Mate service, filtered by agent_id or "
                + "skill_id authorization. Always call this before callMateTool so "
                + "permissions are up to date.";
    }

    @Override
    public JsonNode parameters() {
        return PARAMETERS;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {

        String agentId = (String) params.get("agent_id");
        String skillId = (String) params.get("skill_id");

        log.info(
                "listMateTool: agent_id={} skill_id={}",
                agentId != null ? agentId : "(none)",
                skillId != null ? skillId : "(none)");

        List<CallMateTool.MateToolMeta> tools = client.listTools(agentId, skillId, callMateTool.credentials());

        callMateTool.updateMeta(tools);

        log.info("Listed mate tools: count={}", tools.size());

        StringBuilder sb = new StringBuilder();
        sb.append("Mate tools");

        if (agentId != null) {
            sb.append(" (agent: ").append(agentId).append(")");
        } else if (skillId != null) {
            sb.append(" (skill: ").append(skillId).append(")");
        }

        sb.append(": ").append(tools.size()).append(" tool(s)\n");

        for (CallMateTool.MateToolMeta tool : tools) {
            sb.append("  - ")
                    .append(tool.name())
                    .append(" [")
                    .append(tool.permission() != null ? tool.permission() : "allow")
                    .append("]: ")
                    .append(tool.description() != null ? tool.description() : "")
                    .append("\n");
            if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
                sb.append("      inputSchema: ")
                        .append(MAPPER.writeValueAsString(tool.inputSchema()))
                        .append("\n");
            }
        }

        List<ContentBlock> blocks = List.of(new TextContent(sb.toString()));
        return new AgentToolResult(blocks, null);
    }
}
