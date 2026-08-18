/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.common.dto.AgentInfo;
import com.campusclaw.codingagent.common.dto.QuerySkillToolsResult;
import com.campusclaw.codingagent.common.dto.RequestHeaderInfo;
import com.campusclaw.codingagent.common.dto.ToolInfo;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP implementation of {@link MateToolClient} calling through the Mate inner
 * gateway via {@link MateRestUtil}.
 *
 * <p>Gateway address comes from the {@code mate.innerGWSerive}
 * property/environment variable; requests carry a credential-free
 * {@link RequestHeaderInfo}. {@link #listTools(String, String)} is a two-step
 * query: agent/skill metadata (binding tool IDs) first, then QUERYTOOLS for
 * the tool details. The invoke RPC behind {@link #callTool} remains a stub
 * for internal development (see {@code docs/DEFERRED.md} DEF-007).
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class HttpMateToolClient implements MateToolClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMateToolClient.class);

    /**
     * Agent metadata endpoint: {@code GET /mate-service/v1/agents/{agentId}}.
     */
    protected static final String AGENT_INFO = "/mate-service/v1/agents/";

    /**
     * Skill tool-query endpoint:
     * {@code GET /mate-service/v1/skill/info/query/{skillId}}.
     */
    protected static final String SKILL_TOOLS_QUERY = "/mate-service/v1/skill/info/query/";

    /**
     * Tool details endpoint (QUERYTOOLS):
     * {@code POST /mate-service/v1/runtime/tools/query}.
     */
    protected static final String QUERYTOOLS = "/mate-service/v1/runtime/tools/query";

    /**
     * Path-segment pattern for agent/skill IDs, same as the repo's
     * {@code AgentRuntimeManager.AGENT_ID_PATTERN}: the value becomes a URL
     * path segment, so {@code ..}, {@code ?}, encoded slashes etc. must be
     * rejected before any request is sent.
     */
    private static final Pattern ID_SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /**
     * Address of the Mate inner gateway ({@code mate.innerGWSerive}).
     */
    protected final String mateInnerGwAddress;

    /**
     * REST helper performing the actual gateway calls.
     */
    protected final MateRestUtil mateRestUtil;

    /**
     * Shared Jackson mapper for request/response DTO conversion.
     */
    protected final ObjectMapper mapper;

    /**
     * Creates a client pointed at the Mate inner gateway.
     *
     * @param mateInnerGwAddress the inner gateway base address
     * @param mateRestUtil the REST helper for gateway calls
     * @param mapper shared Jackson mapper
     */
    public HttpMateToolClient(String mateInnerGwAddress, MateRestUtil mateRestUtil, ObjectMapper mapper) {
        this.mateInnerGwAddress = mateInnerGwAddress != null && mateInnerGwAddress.endsWith("/")
                ? mateInnerGwAddress.substring(0, mateInnerGwAddress.length() - 1)
                : mateInnerGwAddress;
        this.mateRestUtil = mateRestUtil;
        this.mapper = mapper;
    }

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId) {
        if (agentId == null && skillId == null) {
            log.warn("listTools called without agent_id or skill_id, returning empty list");
            return List.of();
        }
        String scopedId = agentId != null ? agentId : skillId;
        if (!ID_SEGMENT_PATTERN.matcher(scopedId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + (agentId != null ? "agent" : "skill") + " id for path segment: " + scopedId);
        }
        try {
            List<String> toolIds = agentId != null ? queryToolIdsByAgentId(agentId) : queryToolIdsBySkillId(skillId);
            return queryToolMetaByIds(toolIds);
        } catch (Exception e) {
            log.error("listTools failed: agentId={} skillId={}", agentId, skillId, e);
            throw new IllegalStateException("listTools failed", e);
        }
    }

    @Override
    public ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        try {
            return invokeTool(tool, args, credentials);
        } catch (Exception e) {
            log.error("callTool failed: tool={}", tool, e);
            return new ToolResult("callTool failed: " + e.getMessage(), null, true);
        }
    }

    /**
     * Resolves the bound tool IDs for an agent: GET agent metadata and extract
     * {@code bindingTools[].toolId}.
     *
     * @param agentId the Mate agent ID
     * @return the bound tool ID list
     * @throws Exception when the gateway call or decoding fails
     */
    protected List<String> queryToolIdsByAgentId(String agentId) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress,
                AGENT_INFO + agentId,
                RequestHeaderInfo.builder().build());
        AgentInfo agentInfo = unwrapResult(raw, AgentInfo.class);
        List<String> toolIds = new ArrayList<>();
        if (agentInfo != null && agentInfo.getBindingTools() != null) {
            for (AgentInfo.BindingTool binding : agentInfo.getBindingTools()) {
                toolIds.add(binding.getToolId());
            }
        }
        return toolIds;
    }

    /**
     * Resolves the bound tool IDs for a skill: GET skill tool info and extract
     * {@code bindingTools[].id}.
     *
     * @param skillId the Mate skill ID
     * @return the bound tool ID list
     * @throws Exception when the gateway call or decoding fails
     */
    protected List<String> queryToolIdsBySkillId(String skillId) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress,
                SKILL_TOOLS_QUERY + skillId,
                RequestHeaderInfo.builder().build());
        QuerySkillToolsResult skillResult = unwrapResult(raw, QuerySkillToolsResult.class);
        List<String> toolIds = new ArrayList<>();
        if (skillResult != null && skillResult.getBindingTools() != null) {
            for (var binding : skillResult.getBindingTools()) {
                toolIds.add(binding.getId());
            }
        }
        return toolIds;
    }

    /**
     * Queries full tool metadata by tool ID list (QUERYTOOLS, POST).
     *
     * @param toolIds the tool ID list to query
     * @return full tool metadata list
     * @throws Exception when the gateway call or decoding fails
     * @throws IllegalStateException when resCode is not "0"
     */
    protected List<MateToolMeta> queryToolMetaByIds(List<String> toolIds) throws Exception {
        if (toolIds.isEmpty()) {
            return List.of();
        }
        String body = mapper.writeValueAsString(Map.of("toolIds", toolIds));
        String raw = mateRestUtil.executePostRawRequest(
                mateInnerGwAddress, QUERYTOOLS, RequestHeaderInfo.builder().build(), body);
        JsonNode root = mapper.readTree(raw);
        String resCode = root.path("resCode").asText("");
        if (!"0".equals(resCode)) {
            throw new IllegalStateException("QUERYTOOLS failed: resCode=" + resCode + " resMsg="
                    + root.path("resMsg").asText(""));
        }
        List<ToolInfo> infos =
                mapper.convertValue(root.path("result").path("data"), new TypeReference<List<ToolInfo>>() {});
        return toMeta(infos);
    }

    /**
     * Decodes the standard {@code {resCode, resMsg, result}} envelope and
     * returns the typed {@code result}; fails on a non-zero resCode.
     *
     * @param <T> expected result type
     * @param raw the raw response body
     * @param type the result class
     * @return the decoded result, or null when absent
     * @throws Exception when decoding fails
     * @throws IllegalStateException when resCode is not "0"
     */
    protected <T> T unwrapResult(String raw, Class<T> type) throws Exception {
        JsonNode root = mapper.readTree(raw);
        String resCode = root.path("resCode").asText("");
        if (!"0".equals(resCode)) {
            throw new IllegalStateException("gateway call failed: resCode=" + resCode + " resMsg="
                    + root.path("resMsg").asText(""));
        }
        JsonNode resultNode = root.path("result");
        if (resultNode.isMissingNode() || resultNode.isNull()) {
            return null;
        }
        return mapper.treeToValue(resultNode, type);
    }

    /**
     * Converts gateway {@link ToolInfo} entries to {@link MateToolMeta}.
     *
     * @param infos the gateway tool entries
     * @return converted metadata list
     */
    protected List<MateToolMeta> toMeta(List<ToolInfo> infos) {
        List<MateToolMeta> metas = new ArrayList<>();
        if (infos == null) {
            return metas;
        }
        for (ToolInfo info : infos) {
            metas.add(new MateToolMeta(
                    info.getToolName() != null ? info.getToolName() : info.getToolId(),
                    info.getDescription(),
                    info.getInputSchema(),
                    info.getOutputSchema(),
                    Boolean.TRUE.equals(info.getIsConcurrencySafe()),
                    info.getPermission() != null ? info.getPermission() : "allow"));
        }
        return metas;
    }

    /**
     * Invokes a tool on the Mate server (the real call behind callMateTool).
     *
     * @param tool the tool name to invoke
     * @param args the tool arguments
     * @param credentials agent-handed-down credentials forwarded to the server
     * @return tool execution result
     * @throws UnsupportedOperationException stub — real Mate call not yet wired
     */
    protected ToolResult invokeTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        throw new UnsupportedOperationException("invokeTool: stub (see DEFERRED.md)");
    }
}
