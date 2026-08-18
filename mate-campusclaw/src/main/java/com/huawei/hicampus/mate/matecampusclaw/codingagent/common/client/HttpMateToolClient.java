/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto.RequestHeaderInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto.ToolInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util.MateRestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP implementation of {@link MateToolClient} calling through the Mate inner
 * gateway via {@link MateRestUtil}.
 *
 * <p>Gateway address comes from the {@code mate.innerGWSerive}
 * property/environment variable; requests carry a credential-free
 * {@link RequestHeaderInfo}. {@link #listTools(List)} queries tool metadata via
 * QUERYTOOLS; the invoke RPC behind {@link #callTool(String, Map)} remains a
 * stub for internal development (see {@code docs/DEFERRED.md} DEF-007).
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class HttpMateToolClient implements MateToolClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMateToolClient.class);

    /**
     * QUERYTOOLS path on the Mate inner gateway: query tool metadata by a
     * list of tool IDs.
     */
    protected static final String QUERYTOOLS = "/mate-service/v1/runtime/tools/query";

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
    public List<MateToolMeta> listTools(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            log.warn("listTools called without tool ids, returning empty list");
            return List.of();
        }
        try {
            RequestHeaderInfo headerInfo = RequestHeaderInfo.builder().build();
            String body = mapper.writeValueAsString(Map.of("toolIds", toolIds));
            String raw = mateRestUtil.executePostRawRequest(mateInnerGwAddress, QUERYTOOLS, headerInfo, body);
            var root = mapper.readTree(raw);
            if (!"0".equals(root.path("resCode").asText(""))) {
                throw new IllegalStateException(
                        "QUERYTOOLS failed: resCode=" + root.path("resCode").asText("") + " resMsg="
                                + root.path("resMsg").asText(""));
            }
            List<ToolInfo> infos =
                    mapper.convertValue(root.path("result").path("data"), new TypeReference<List<ToolInfo>>() {});
            return toMeta(infos);
        } catch (Exception e) {
            log.error("listTools failed: count={} ", toolIds.size(), e);
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
