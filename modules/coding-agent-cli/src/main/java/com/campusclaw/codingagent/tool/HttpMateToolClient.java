/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import com.campusclaw.codingagent.tool.CallMateTool.MateCredentials;
import com.campusclaw.codingagent.tool.CallMateTool.MateToolMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP implementation of {@link CallMateTool.MateToolClient}.
 * Talks to the Mate tool server via POST /list_tools and POST /call_tool.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/13]
 */
public class HttpMateToolClient implements CallMateTool.MateToolClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMateToolClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public HttpMateToolClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId, MateCredentials credentials) {
        try {
            Map<String, Object> body = new HashMap<>();
            if (agentId != null) {
                body.put("agent_id", agentId);
            }
            if (skillId != null) {
                body.put("skill_id", skillId);
            }

            HttpResponse<String> resp = post("/list_tools", body, credentials);
            if (resp.statusCode() != 200) {
                log.error("list_tools failed: HTTP {} body={}", resp.statusCode(), resp.body());
                throw new IllegalStateException("list_tools failed: HTTP " + resp.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(resp.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolList = (List<Map<String, Object>>) result.get("tools");

            List<MateToolMeta> metas = new ArrayList<>();
            for (Map<String, Object> t : toolList) {
                metas.add(new MateToolMeta(
                        (String) t.get("name"),
                        (String) t.get("description"),
                        (Map<String, Object>) t.get("inputScheme"),
                        (Map<String, Object>) t.get("outputScheme"),
                        Boolean.TRUE.equals(t.get("isConcurrencySafe")),
                        (String) t.get("permission")));
            }
            return metas;
        } catch (Exception e) {
            log.error("list_tools error", e);
            throw new IllegalStateException("list_tools error", e);
        }
    }

    @Override
    public ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tool", tool);
            if (args != null) {
                body.put("args", args);
            }

            HttpResponse<String> resp = post("/call_tool", body, credentials);
            if (resp.statusCode() != 200) {
                log.error("call_tool failed: HTTP {} body={}", resp.statusCode(), resp.body());
                return new ToolResult("call_tool failed: HTTP " + resp.statusCode(), null, true);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(resp.body(), Map.class);
            return new ToolResult(
                    (String) result.get("content"),
                    null,
                    Boolean.TRUE.equals(result.get("is_error")));
        } catch (Exception e) {
            log.error("call_tool error", e);
            return new ToolResult("call_tool error: " + e.getMessage(), null, true);
        }
    }

    // ==================== internal ====================

    private HttpResponse<String> post(String path, Map<String, Object> body,
            MateCredentials credentials) throws Exception {
        byte[] json = MAPPER.writeValueAsBytes(body);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json; charset=UTF-8")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));

        // Auth headers
        if (credentials != null) {
            if (credentials.xHwId() != null) {
                builder.header("X-HW-ID", credentials.xHwId());
            }
            if (credentials.xHwAppKey() != null) {
                builder.header("X-HW-APPKEY", credentials.xHwAppKey());
            }
            if (credentials.authorization() != null) {
                builder.header("Authorization", credentials.authorization());
            }
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
