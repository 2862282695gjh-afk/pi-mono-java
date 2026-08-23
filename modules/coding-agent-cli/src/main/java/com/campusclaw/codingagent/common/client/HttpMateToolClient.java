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
import com.campusclaw.codingagent.common.dto.RequestHeaderInfo;
import com.campusclaw.codingagent.common.dto.SkillInfoResult;
import com.campusclaw.codingagent.common.dto.ToolInfo;
import com.campusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.campusclaw.codingagent.common.util.MateRestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 {@link MateRestUtil} 访问 Mate 内部网关的 {@link MateToolClient} HTTP 实现。
 *
 * <p>网关地址和出站接口路径均由配置注入，请求使用不携带凭据的 {@link RequestHeaderInfo}。
 * {@link #listAgentTools(String)} 与 {@link #listSkillTools(String)} 先查询绑定工具标识，再批量查询工具详情。
 * {@link #callTool} 背后的执行 RPC 经 {@code tool-execute-path-template} 调用网关，
 * 携带 {@link com.campusclaw.codingagent.tool.mate.MateCredentialResolver} 解析的完整凭据。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class HttpMateToolClient implements MateToolClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMateToolClient.class);

    /** Mate 内部网关地址，对应 {@code mate.innerGWSerive}。 */
    protected final String mateInnerGwAddress;

    /** Agent 元数据查询路径前缀。 */
    protected final String agentInfoPathPrefix;

    /** Skill 绑定工具查询路径前缀。 */
    protected final String skillToolsQueryPathPrefix;

    /** 工具元数据批量查询路径。 */
    protected final String toolMetadataQueryPath;

    /** 工具执行路径模板，{@code %s} 为工具标识占位。 */
    protected final String toolExecutePathTemplate;

    /** 执行网关请求的 REST 工具。 */
    protected final MateRestUtil mateRestUtil;

    /** 请求和响应 DTO 转换使用的 Jackson 映射器。 */
    protected final ObjectMapper mapper;

    /**
     * 创建访问 Mate 内部网关的客户端。
     *
     * @param mateInnerGwAddress 内部网关基础地址
     * @param agentInfoPathPrefix Agent 元数据查询路径前缀
     * @param skillToolsQueryPathPrefix Skill 绑定工具查询路径前缀
     * @param toolMetadataQueryPath 工具元数据批量查询路径
     * @param toolExecutePathTemplate 工具执行路径模板，{@code %s} 为工具标识占位
     * @param mateRestUtil 网关 REST 工具
     * @param mapper Jackson 映射器
     */
    public HttpMateToolClient(
            String mateInnerGwAddress,
            String agentInfoPathPrefix,
            String skillToolsQueryPathPrefix,
            String toolMetadataQueryPath,
            String toolExecutePathTemplate,
            MateRestUtil mateRestUtil,
            ObjectMapper mapper) {
        this.mateInnerGwAddress = mateInnerGwAddress != null && mateInnerGwAddress.endsWith("/")
                ? mateInnerGwAddress.substring(0, mateInnerGwAddress.length() - 1)
                : mateInnerGwAddress;
        this.agentInfoPathPrefix = agentInfoPathPrefix;
        this.skillToolsQueryPathPrefix = skillToolsQueryPathPrefix;
        this.toolMetadataQueryPath = toolMetadataQueryPath;
        this.toolExecutePathTemplate = toolExecutePathTemplate;
        this.mateRestUtil = mateRestUtil;
        this.mapper = mapper;
    }

    @Override
    public List<MateToolMeta> listAgentTools(String agentId) {
        requireScopedId(agentId, ResourceIdentifierPatterns.AGENT_ID_PATTERN, "agent");
        try {
            return queryOrderedToolMeta(queryToolIdsByAgentId(agentId));
        } catch (Exception exception) {
            log.error("listAgentTools failed: agentId={}", agentId, exception);
            throw new IllegalStateException("listAgentTools failed", exception);
        }
    }

    @Override
    public List<MateToolMeta> listSkillTools(String skillId) {
        requireScopedId(skillId, ResourceIdentifierPatterns.SKILL_ID_PATTERN, "skill");
        try {
            return queryOrderedToolMeta(queryToolIdsBySkillId(skillId));
        } catch (Exception exception) {
            log.error("listSkillTools failed: skillId={}", skillId, exception);
            throw new IllegalStateException("listSkillTools failed", exception);
        }
    }

    @Override
    public ToolResult callTool(String toolId, Map<String, Object> args, MateCredentials credentials) {
        try {
            return invokeTool(toolId, args, credentials);
        } catch (Exception e) {
            log.error("callTool failed: toolId={}", toolId, e);
            return new ToolResult("Mate tool execution request failed", null, true);
        }
    }

    /**
     * 查询 Agent 元数据并提取 {@code bindingTools[].toolId}。
     *
     * @param agentId Mate Agent 标识
     * @return 绑定工具标识列表
     * @throws Exception 网关调用或响应解析失败时抛出
     */
    protected List<String> queryToolIdsByAgentId(String agentId) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress,
                agentInfoPathPrefix + agentId,
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
     * 查询 Skill 绑定工具信息并提取 {@code bindingTools[].id}。
     *
     * @param skillId Mate Skill 标识
     * @return 绑定工具标识列表
     * @throws Exception 网关调用或响应解析失败时抛出
     */
    protected List<String> queryToolIdsBySkillId(String skillId) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress,
                skillToolsQueryPathPrefix + skillId,
                RequestHeaderInfo.builder().build());
        SkillInfoResult skillResult = unwrapResult(raw, SkillInfoResult.class);
        List<String> toolIds = new ArrayList<>();
        if (skillResult != null && skillResult.getBindingTools() != null) {
            for (var binding : skillResult.getBindingTools()) {
                toolIds.add(binding.getId());
            }
        }
        return toolIds;
    }

    /**
     * 按工具标识列表批量查询完整工具元数据。
     *
     * @param toolIds 待查询的工具标识列表
     * @return 完整工具元数据列表
     * @throws Exception 网关调用或响应解析失败时抛出
     * @throws IllegalStateException {@code resCode} 不为 {@code 0} 时抛出
     */
    protected List<MateToolMeta> queryToolMetaByIds(List<String> toolIds) throws Exception {
        if (toolIds.isEmpty()) {
            return List.of();
        }
        requireToolIds(toolIds);
        String body = mapper.writeValueAsString(Map.of("toolIds", toolIds));
        String raw = mateRestUtil.executePostRawRequest(
                mateInnerGwAddress,
                toolMetadataQueryPath,
                RequestHeaderInfo.builder().build(),
                body);
        JsonNode root = mapper.readTree(raw);
        String resCode = root.path("resCode").asText("");
        if (!"0".equals(resCode)) {
            throw new IllegalStateException("tool metadata query failed: resCode=" + resCode + " resMsg="
                    + root.path("resMsg").asText(""));
        }
        List<ToolInfo> infos =
                mapper.convertValue(root.path("result").path("data"), new TypeReference<List<ToolInfo>>() {});
        return toMeta(infos);
    }

    private List<MateToolMeta> queryOrderedToolMeta(List<String> toolIds) throws Exception {
        if (new java.util.HashSet<>(toolIds).size() != toolIds.size()) {
            throw new IllegalStateException("Duplicate bound tool id");
        }
        List<MateToolMeta> metadata = queryToolMetaByIds(toolIds);
        Map<String, MateToolMeta> metadataById = new java.util.HashMap<>();
        for (MateToolMeta meta : metadata) {
            if (meta.toolId() != null && metadataById.put(meta.toolId(), meta) != null) {
                throw new IllegalStateException("Duplicate tool metadata id");
            }
        }
        List<MateToolMeta> ordered = new ArrayList<>();
        for (String toolId : toolIds) {
            MateToolMeta meta = metadataById.get(toolId);
            if (meta == null) {
                throw new IllegalStateException("Missing metadata for bound tool");
            }
            ordered.add(meta);
        }
        return List.copyOf(ordered);
    }

    private static void requireScopedId(String id, Pattern pattern, String scope) {
        if (id == null || !pattern.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid " + scope + " id for path segment");
        }
    }

    private static void requireToolIds(List<String> toolIds) {
        for (String toolId : toolIds) {
            if (toolId == null
                    || !ResourceIdentifierPatterns.TOOL_ID_PATTERN
                            .matcher(toolId)
                            .matches()) {
                throw new IllegalArgumentException("Invalid tool id: " + toolId);
            }
        }
    }

    /**
     * 解析标准 {@code {resCode, resMsg, result}} 信封并返回指定类型的 {@code result}。
     *
     * @param <T> 结果类型
     * @param raw 原始响应体
     * @param type 结果类
     * @return 解析后的结果；不存在时返回 {@code null}
     * @throws Exception 解析失败时抛出
     * @throws IllegalStateException {@code resCode} 不为 {@code 0} 时抛出
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
     * 将网关 {@link ToolInfo} 转换为 {@link MateToolMeta}。
     *
     * @param infos 网关工具元数据列表
     * @return 转换后的工具元数据列表
     */
    protected List<MateToolMeta> toMeta(List<ToolInfo> infos) {
        List<MateToolMeta> metas = new ArrayList<>();
        if (infos == null) {
            return metas;
        }
        for (ToolInfo info : infos) {
            metas.add(new MateToolMeta(
                    info.getId(),
                    info.getName(),
                    info.getDescription(),
                    info.getInputSchema(),
                    info.getOutputSchema(),
                    Boolean.TRUE.equals(info.getIsConcurrencySafe()),
                    info.getPermission() != null ? info.getPermission() : "allow"));
        }
        return metas;
    }

    /**
     * 调用 Mate 服务端工具：POST 工具参数（按工具 inputSchema 序列化）到
     * {@code toolExecutePathTemplate} 展开后的执行端点，请求体为
     * {@code {"arguments": {...}}}（CampusMate 执行接口的参数包装契约）。Header 与
     * listTools 同源构建，并按 Agent 下发凭据补可选的鉴权 Header
     * （AppKey 模式 {@code X-HW-ID} + {@code X-HW-APPKEY}，JWT 模式
     * {@code X-HW-ID} + {@code Authorization}）。
     *
     * <p>凭据缺失时直接拒绝执行而非匿名调用——部署未接线凭据来源
     * （{@code CallMateTool.resolveCredentials()} 返回 null）时快速失败，
     * 避免未认证请求发出。
     *
     * @param toolId 待调用的工具标识
     * @param args 工具参数；按工具 inputSchema 作为请求体
     * @param credentials Agent 下发并透传到服务端的凭据；null 时拒绝执行
     * @return 工具执行结果；网关失败或凭据缺失时为 isError=true
     * @throws IllegalArgumentException 工具标识不满足路径段约束时抛出
     */
    protected ToolResult invokeTool(String toolId, Map<String, Object> args, MateCredentials credentials) {
        if (toolId == null
                || !ResourceIdentifierPatterns.TOOL_ID_PATTERN.matcher(toolId).matches()) {
            throw new IllegalArgumentException("Invalid tool id for path segment");
        }
        if (credentials == null || !credentials.isComplete()) {
            log.error(
                    "invokeTool called without complete credentials: tool={} — wire"
                            + " CallMateTool.resolveCredentials() to return X-HW-ID plus exactly one of"
                            + " X-HW-APPKEY / Authorization",
                    toolId);
            return new ToolResult(
                    "invokeTool refused: incomplete credentials (need X-HW-ID plus exactly one of"
                            + " X-HW-APPKEY / Authorization)",
                    null,
                    true);
        }
        try {
            RequestHeaderInfo headerInfo = RequestHeaderInfo.builder()
                    .xHwId(credentials.xHwId())
                    .xHwAppKey(credentials.xHwAppKey())
                    .authorization(credentials.authorization())
                    .build();

            // CampusMate 执行接口契约:参数需包一层 arguments 包装。
            String body = mapper.writeValueAsString(Map.of("arguments", args != null ? args : Map.of()));
            String path = toolExecutePathTemplate.replace("%s", toolId);
            String raw = mateRestUtil.executePostRawRequest(mateInnerGwAddress, path, headerInfo, body);
            JsonNode root = mapper.readTree(raw);
            String resCode = root.path("resCode").asText("");
            if (!"0".equals(resCode)) {
                return new ToolResult(
                        "tool execute failed: resCode=" + resCode + " resMsg="
                                + root.path("resMsg").asText(""),
                        null,
                        true);
            }
            JsonNode resultNode = root.path("result");
            String content = resultNode.isMissingNode() || resultNode.isNull() ? "" : resultNode.toString();
            return new ToolResult(content, null, false);
        } catch (Exception e) {
            log.error("invokeTool failed: toolId={}", toolId, e);
            return new ToolResult("Mate tool execution request failed", null, true);
        }
    }
}
