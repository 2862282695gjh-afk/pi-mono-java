/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import com.campusclaw.agent.tool.AgentToolResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke test for ListMateTool + CallMateTool against MockMateToolServer.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/13]
 */
@SuppressWarnings("checkstyle:no_system_out_err")
public class MateToolSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(MateToolSmokeTest.class);

    /**
     * Entry point.
     *
     * @param args unused
     * @throws Exception if any tool execution fails
     */
    public static void main(String[] args) throws Exception {
        CallMateTool callMateTool = setup();
        testList(callMateTool);
        testAllow(callMateTool);
        testAsk(callMateTool);
        testDeny(callMateTool);
        testUnknown(callMateTool);
        System.out.println("\n===== ALL TESTS DONE =====");
    }

    private static CallMateTool setup() {
        var client = new HttpMateToolClient("http://127.0.0.1:9999");
        var creds = CallMateTool.MateCredentials.appKey("hw-id-001", "hw-key-001");
        CallMateTool.MateApprovalUI ui = (tool, a, desc) -> {
            System.out.println("  [auto-approve] " + tool);
            return true;
        };
        return new CallMateTool(client, ui, creds);
    }

    private static void testList(CallMateTool callMateTool) throws Exception {
        var listMateTool = new ListMateTool(
                new HttpMateToolClient("http://127.0.0.1:9999"), callMateTool);
        System.out.println("\n===== 1. listMateTool(agent=agent-001) =====");
        AgentToolResult r = listMateTool.execute("t1",
                Map.of("agent_id", "agent-001"), null, null);
        print(r);
    }

    private static void testAllow(CallMateTool cmt) throws Exception {
        System.out.println("\n===== 2. callMateTool(query) — allow =====");
        print(cmt.execute("t2",
                Map.of("tool", "query", "args", Map.of("sql", "SELECT 1")), null, null));
    }

    private static void testAsk(CallMateTool cmt) throws Exception {
        System.out.println("\n===== 3. callMateTool(export) — ask =====");
        print(cmt.execute("t3",
                Map.of("tool", "export", "args", Map.of()), null, null));
    }

    private static void testDeny(CallMateTool cmt) throws Exception {
        System.out.println("\n===== 4. callMateTool(delete) — deny =====");
        print(cmt.execute("t4",
                Map.of("tool", "delete", "args", Map.of()), null, null));
    }

    private static void testUnknown(CallMateTool cmt) throws Exception {
        System.out.println("\n===== 5. callMateTool(unknown) =====");
        print(cmt.execute("t5",
                Map.of("tool", "unknown", "args", Map.of()), null, null));
    }

    private static void print(AgentToolResult r) {
        var sb = new StringBuilder();
        for (var b : r.content()) {
            sb.append(b.toString());
        }
        System.out.println("  result: " + sb);
    }
}
