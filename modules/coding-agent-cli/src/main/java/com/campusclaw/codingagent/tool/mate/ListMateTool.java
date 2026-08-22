/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 {@code agent_id} 或 {@code skill_id} 查询 Mate 服务中已绑定的工具。
 *
 * <p>该工具无状态：查询结果仅返回给模型，不在调用之间缓存。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class ListMateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListMateTool.class);

    private final MateToolClient client;

    private final MateToolSessionCache sessionCache;

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
     * 创建 Mate 工具列表查询工具。
     *
     * @param client Mate 工具服务客户端
     * @param sessionCache 会话级工具名→标识映射缓存;每次查询后硬性全量刷新
     */
    public ListMateTool(MateToolClient client, MateToolSessionCache sessionCache) {
        this.client = client;
        this.sessionCache = sessionCache;
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
                + "skill_id authorization. Always call this before callMateTool.";
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

        List<MateToolMeta> tools = client.listTools(agentId, skillId);

        if (sessionCache != null) {
            sessionCache.refresh(tools);
        }

        log.info("Listed mate tools: count={}", tools.size());

        StringBuilder sb = new StringBuilder();
        sb.append("Mate tools");

        if (agentId != null) {
            sb.append(" (agent: ").append(agentId).append(")");
        } else if (skillId != null) {
            sb.append(" (skill: ").append(skillId).append(")");
        }

        sb.append(": ").append(tools.size()).append(" tool(s)\n");

        for (MateToolMeta tool : tools) {
            sb.append("  - ")
                    .append(tool.toolName() != null ? tool.toolName() : tool.toolId())
                    .append(" (id: ")
                    .append(tool.toolId())
                    .append("): ")
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
