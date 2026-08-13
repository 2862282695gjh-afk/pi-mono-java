/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock Mate tool server for local testing of ListMateTool / CallMateTool.
 *
 * <p>Starts an HTTP server on port 9999 with two endpoints:
 * <ul>
 *   <li>{@code POST /list_tools} — returns tool metadata (with permission allow/ask/deny)</li>
 *   <li>{@code POST /call_tool} — returns a fixed reply for the requested tool</li>
 * </ul>
 *
 * <p>Authentication: checks X-HW-ID + X-HW-APPKEY header (AppKey mode).
 * Returns 401 Huawei-style JSON if missing.
 *
 * <p>Run: {@code java MockMateToolServer}
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/13]
 */
@SuppressWarnings("checkstyle:no_system_out_err")
public class MockMateToolServer {

    private static final Logger log = LoggerFactory.getLogger(MockMateToolServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 9999;

    // Valid credentials (mock)
    private static final String VALID_HW_ID = "hw-id-001";
    private static final String VALID_APP_KEY = "hw-key-001";

    // ---- mock tool metadata ----
    private static final List<Map<String, Object>> ALL_TOOLS = List.of(
            toolMeta("query",  "Query campus data",                "allow", true),
            toolMeta("chart",  "Generate a chart from data",       "allow", true),
            toolMeta("export", "Export query results to file",     "ask",   false),
            toolMeta("delete", "Delete data records",              "deny",  false)
    );

    // ---- authorized tool_ids per agent/skill (mock) ----
    private static final Map<String, List<String>> AUTHORIZED = new HashMap<>();
    static {
        AUTHORIZED.put("agent-001", List.of("query", "chart", "export"));
        AUTHORIZED.put("skill-001", List.of("query", "chart"));
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/list_tools", new ListToolsHandler());
        server.createContext("/call_tool", new CallToolHandler());
        server.setExecutor(null);
        server.start();
        log.info("Mock Mate server started on port {}", PORT);
        System.out.println("Mock Mate server running on http://127.0.0.1:" + PORT);
        System.out.println("Endpoints: POST /list_tools, POST /call_tool");
        System.out.println("Credentials: X-HW-ID=" + VALID_HW_ID + " X-HW-APPKEY=" + VALID_APP_KEY);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ==================== Handlers ====================

    static class ListToolsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            if (!checkAuth(exchange)) {
                sendHuawei401(exchange);
                return;
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                String agentId = (String) body.get("agent_id");
                String skillId = (String) body.get("skill_id");

                List<Map<String, Object>> result = new ArrayList<>();

                if (agentId != null && AUTHORIZED.containsKey(agentId)) {
                    List<String> toolIds = AUTHORIZED.get(agentId);
                    for (Map<String, Object> tool : ALL_TOOLS) {
                        if (toolIds.contains(tool.get("name"))) {
                            result.add(tool);
                        }
                    }
                    log.info("list_tools: agent_id={} → {} tools", agentId, result.size());
                } else if (skillId != null && AUTHORIZED.containsKey(skillId)) {
                    List<String> toolIds = AUTHORIZED.get(skillId);
                    for (Map<String, Object> tool : ALL_TOOLS) {
                        if (toolIds.contains(tool.get("name"))) {
                            result.add(tool);
                        }
                    }
                    log.info("list_tools: skill_id={} → {} tools", skillId, result.size());
                } else {
                    result.addAll(ALL_TOOLS);
                    log.info("list_tools: no filter → {} tools", result.size());
                }

                sendJson(exchange, 200, Map.of("tools", result));
            } catch (Exception e) {
                log.error("list_tools error", e);
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    static class CallToolHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            if (!checkAuth(exchange)) {
                sendHuawei401(exchange);
                return;
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                String tool = (String) body.get("tool");

                // Mock reply: always succeed with a fixed message
                String content = "Mock result from tool '" + tool + "': 好的";
                Map<String, Object> result = Map.of(
                        "content", content,
                        "is_error", false);
                log.info("call_tool: tool={} → {}", tool, content);
                sendJson(exchange, 200, result);
            } catch (Exception e) {
                log.error("call_tool error", e);
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            }
        }
    }

    // ==================== Auth ====================

    private static boolean checkAuth(HttpExchange exchange) {
        String xHwId = exchange.getRequestHeaders().getFirst("X-HW-ID");
        String xHwAppKey = exchange.getRequestHeaders().getFirst("X-HW-APPKEY");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");

        // AppKey mode
        if (VALID_HW_ID.equals(xHwId) && VALID_APP_KEY.equals(xHwAppKey)) {
            return true;
        }

        // JWT mode (mock: any Bearer token with valid X-HW-ID)
        if (VALID_HW_ID.equals(xHwId) && authorization != null
                && authorization.startsWith("Bearer ")) {
            return true;
        }
        return false;
    }

    private static void sendHuawei401(HttpExchange exchange) throws IOException {
        sendJson(exchange, 401, Map.of(
                "status", 401,
                "source", "Huawei API-Gateway",
                "time", java.time.LocalDateTime.now().toString(),
                "message", "Authorzation failed"));
    }

    // ==================== Utils ====================

    private static Map<String, Object> toolMeta(String name, String desc,
            String permission, boolean safe) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("description", desc);
        m.put("permission", permission);
        m.put("isConcurrencySafe", safe);
        m.put("inputScheme", Map.of("type", "object", "properties", Map.of()));
        m.put("outputScheme", Map.of("type", "string"));
        return m;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body)
            throws IOException {
        byte[] resp = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}
