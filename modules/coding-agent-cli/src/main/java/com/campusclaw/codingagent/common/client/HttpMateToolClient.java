/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.common.client.mate.MateToolResponseException;
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
 * <p>网关地址和出站接口路径均由配置注入。发现与执行请求使用当前 Agent 执行的不可变凭据
 * 快照；发现端点允许空凭据，执行端点在发出请求前校验最低完整性。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
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
    public List<MateToolMeta> listAgentTools(String agentId, MateCredentials credentials) {
        requireScopedId(agentId, ResourceIdentifierPatterns.AGENT_ID_PATTERN, "agent");
        try {
            return queryOrderedToolMeta(queryToolIdsByAgentId(agentId, credentials), credentials);
        } catch (Exception exception) {
            log.error("listAgentTools failed: agentId={}", agentId, exception);
            throw publicFailure("listAgentTools", exception);
        }
    }

    @Override
    public List<MateToolMeta> listSkillTools(String skillId, MateCredentials credentials) {
        requireScopedId(skillId, ResourceIdentifierPatterns.SKILL_ID_PATTERN, "skill");
        try {
            return queryOrderedToolMeta(queryToolIdsBySkillId(skillId, credentials), credentials);
        } catch (Exception exception) {
            log.error("listSkillTools failed: skillId={}", skillId, exception);
            throw publicFailure("listSkillTools", exception);
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
     * @param credentials 本次执行的凭据快照
     * @return 绑定工具标识列表
     * @throws Exception 网关调用或响应解析失败时抛出
     */
    protected List<String> queryToolIdsByAgentId(String agentId, MateCredentials credentials) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress, agentInfoPathPrefix + agentId, toHeaderInfo(credentials));
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
     * @param credentials 本次执行的凭据快照
     * @return 绑定工具标识列表
     * @throws Exception 网关调用或响应解析失败时抛出
     */
    protected List<String> queryToolIdsBySkillId(String skillId, MateCredentials credentials) throws Exception {
        String raw = mateRestUtil.executeGetRawRequest(
                mateInnerGwAddress, skillToolsQueryPathPrefix + skillId, toHeaderInfo(credentials));
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
     * @param credentials 本次执行的凭据快照
     * @return 完整工具元数据列表
     * @throws Exception 网关调用或读取失败时抛出
     * @throws MateToolResponseException 响应体为空或 {@code result.data} 缺失/形状不符时抛出
     */
    protected List<MateToolMeta> queryToolMetaByIds(List<String> toolIds, MateCredentials credentials)
            throws Exception {
        if (toolIds.isEmpty()) {
            return List.of();
        }
        requireToolIds(toolIds);
        String body = mapper.writeValueAsString(Map.of("toolIds", toolIds));
        String raw = mateRestUtil.executePostRawRequest(
                mateInnerGwAddress, toolMetadataQueryPath, toHeaderInfo(credentials), body);
        JsonNode root = readRoot(raw);
        JsonNode data = root.path("result").path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new MateToolResponseException("result.data is missing");
        }
        List<ToolInfo> infos;
        try {
            infos = mapper.convertValue(data, new TypeReference<List<ToolInfo>>() {});
        } catch (IllegalArgumentException e) {
            throw new MateToolResponseException("result.data is not a tool metadata list", e);
        }
        return toMeta(infos);
    }

    private List<MateToolMeta> queryOrderedToolMeta(List<String> toolIds, MateCredentials credentials)
            throws Exception {
        if (new java.util.HashSet<>(toolIds).size() != toolIds.size()) {
            throw new IllegalStateException("Duplicate bound tool id");
        }
        List<MateToolMeta> metadata = queryToolMetaByIds(toolIds, credentials);
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
     * 解析响应中的 {@code result} 字段并转换为指定类型；响应结构包含 {@code resCode}、
     * {@code resMsg} 和 {@code result} 字段。本客户端不按 {@code resCode} 预判处理结果。
     * {@code result} 是元数据端点的必需字段:缺失或为 {@code null} 都视为解析失败,
     * 不允许把畸形响应当作"没有绑定工具"。
     *
     * @param <T> 结果类型
     * @param raw 原始响应体
     * @param type 结果类
     * @return 解析后的结果；{@code result} 缺失或为 {@code null} 时抛出异常
     * @throws Exception 请求或读取失败时抛出
     * @throws MateToolResponseException 响应体为空、非法 JSON、{@code result} 缺失/为 null 或形状不符时抛出
     */
    protected <T> T unwrapResult(String raw, Class<T> type) throws Exception {
        JsonNode root = readRoot(raw);
        JsonNode resultNode = root.path("result");
        if (resultNode.isMissingNode() || resultNode.isNull()) {
            throw new MateToolResponseException("result is missing or null");
        }
        try {
            return mapper.treeToValue(resultNode, type);
        } catch (IOException e) {
            throw new MateToolResponseException("result does not match " + type.getSimpleName(), e);
        }
    }

    private JsonNode readRoot(String raw) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (IOException e) {
            throw new MateToolResponseException("response body is not valid JSON", e);
        }
        if (root == null || root.isMissingNode()) {
            throw new MateToolResponseException("response body is empty");
        }
        return root;
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
     * listTools 同源构建，并按本次 Agent 执行快照补充鉴权 Header
     * （AppKey 模式 {@code X-HW-ID} + {@code X-HW-APPKEY}，JWT 模式
     * {@code X-HW-ID} + {@code Authorization}）。
     *
     * <p>凭据缺失时直接拒绝执行而非匿名调用，避免未认证请求发出。
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
                    "invokeTool called without complete credentials: tool={}; wire"
                            + " execution credentials to include X-HW-ID plus X-HW-APPKEY or Authorization",
                    toolId);
            return new ToolResult(
                    "invokeTool refused: incomplete credentials (need X-HW-ID plus X-HW-APPKEY or Authorization)",
                    null,
                    true);
        }
        try {
            RequestHeaderInfo headerInfo = toHeaderInfo(credentials);

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

    // 稳定错误码异常原样透出供公开边界映射;其余异常包装为通用失败。
    private static RuntimeException publicFailure(String operation, Exception exception) {
        if (exception instanceof MateToolResponseException) {
            return (MateToolResponseException) exception;
        }
        return new IllegalStateException(operation + " failed", exception);
    }

    private static RequestHeaderInfo toHeaderInfo(MateCredentials credentials) {
        MateCredentials snapshot = credentials == null ? MateCredentials.empty() : credentials;
        return RequestHeaderInfo.builder()
                .xHwId(snapshot.xHwId())
                .xHwAppKey(snapshot.xHwAppKey())
                .authorization(snapshot.authorization())
                .build();
    }
}
