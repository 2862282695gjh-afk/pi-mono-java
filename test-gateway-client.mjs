#!/usr/bin/env node
/**
 * 模拟 OpenClaw GatewayClient 的 one-shot callGateway 流程
 * 测试 sessions.send 能否通过 response frame 收到最终结果。
 *
 * Usage:  node test-gateway-client.mjs [url] [token]
 * Default: ws://127.0.0.1:18788/  token=test-token
 */

import WebSocket from "ws";

const url = process.argv[2] || "ws://127.0.0.1:18788/";
const token = process.argv[3] || "test-token";

let passed = 0;
let failed = 0;

function assert(label, condition, detail) {
  if (condition) {
    console.log(`  ✅ ${label}`);
    passed++;
  } else {
    console.log(`  ❌ ${label}${detail ? " — " + detail : ""}`);
    failed++;
  }
}

// ── helpers ──────────────────────────────────────────────────────
function waitFor(ws, predicate, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout after " + timeoutMs + "ms")), timeoutMs);
    ws.once("message", (raw) => {
      clearTimeout(timer);
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { reject(new Error("invalid JSON")); return; }
      if (predicate(msg)) resolve(msg);
      else waitFor(ws, predicate, timeoutMs).then(resolve).catch(reject);
    });
  });
}

function send(ws, obj) {
  ws.send(JSON.stringify(obj));
}

// ── main ─────────────────────────────────────────────────────────
async function run() {
  console.log(`\n🔗 Connecting to ${url}\n`);

  const ws = new WebSocket(url);
  const open = new Promise((res, rej) => {
    ws.on("open", res);
    ws.on("error", rej);
  });
  await open;
  console.log("  WebSocket connected.\n");

  // ── Test 1: connect.challenge + connect + hello-ok ──────────
  console.log("── Test 1: Auth handshake ──");
  const challenge = await waitFor(ws, (m) => m.event === "connect.challenge");
  assert("connect.challenge received", challenge.type === "event");
  assert("nonce present", typeof challenge.payload?.nonce === "string");
  console.log();

  const connectReq = {
    type: "req",
    id: "connect-1",
    method: "connect",
    params: {
      minProtocol: 3,
      maxProtocol: 3,
      client: { id: "test-client", version: "0.1.0", platform: "test" },
      auth: { token },
    },
  };
  send(ws, connectReq);

  const helloRes = await waitFor(ws, (m) => m.id === "connect-1");
  assert("hello-ok received", helloRes.type === "res" && helloRes.ok === true);
  assert("payload.type === hello-ok", helloRes.payload?.type === "hello-ok");
  assert("features.methods includes sessions.send",
    Array.isArray(helloRes.payload?.features?.methods) &&
    helloRes.payload.features.methods.includes("sessions.send"));
  console.log();

  // ── Test 2: sessions.send — expect accepted then final ───────
  console.log("── Test 2: sessions.send (one-shot flow) ──");

  const testMessage = "你好，请简短回复";
  const sendReqId = "send-test-1";

  send(ws, {
    type: "req",
    id: sendReqId,
    method: "sessions.send",
    params: { key: "test-session", message: testMessage },
  });

  // Step 2a: receive "accepted" response
  const acceptedRes = await waitFor(ws, (m) => m.id === sendReqId, 5000);
  assert("accepted response received", acceptedRes.type === "res" && acceptedRes.ok === true);
  assert("status === accepted", acceptedRes.payload?.status === "accepted",
    `got: ${acceptedRes.payload?.status}`);
  console.log("  ⏳ Waiting for final response frame (agent processing)...\n");

  // Step 2b: receive final response frame (type: "res", same id, status: "final")
  // This is the key test — OpenClaw GatewayClient expects this format
  try {
    const finalRes = await waitFor(ws, (m) =>
      m.id === sendReqId && m.type === "res" && m.payload?.status === "final",
      60000  // 60s timeout for agent processing
    );
    assert("final response frame received (type: res)", finalRes.type === "res");
    assert("final response has same id", finalRes.id === sendReqId);
    assert("ok === true", finalRes.ok === true);
    assert("status === final", finalRes.payload?.status === "final",
      `got: ${finalRes.payload?.status}`);
    assert("payload has message", finalRes.payload?.message != null,
      "message field missing from payload");
    assert("message.content is string",
      typeof finalRes.payload?.message?.content === "string",
      `got type: ${typeof finalRes.payload?.message?.content}`);

    if (finalRes.payload?.message?.content) {
      const content = finalRes.payload.message.content;
      console.log(`\n  📨 Agent response (${content.length} chars):\n`);
      console.log(`  ─────────────────────────────────────`);
      // Truncate display for very long responses
      const display = content.length > 300 ? content.slice(0, 300) + "..." : content;
      console.log(`  ${display}`);
      console.log(`  ─────────────────────────────────────\n`);
    }
  } catch (err) {
    if (err.message.includes("timeout")) {
      console.log(`  ⚠️  No final response frame received within timeout`);
      console.log(`     The agent may still be processing, or the AgentResponseEvent is not wired up.\n`);
      assert("final response frame received", false, "timeout — agent did not send final res frame");
    } else {
      console.log(`  ⚠️  Error: ${err.message}\n`);
      assert("final response frame received", false, err.message);
    }
  }

  // ── Summary ───────────────────────────────────────────────────
  ws.close();
  console.log("═══════════════════════════════════");
  console.log(`  ✅ Passed: ${passed}`);
  console.log(`  ❌ Failed: ${failed}`);
  console.log("═══════════════════════════════════\n");

  process.exit(failed > 0 ? 1 : 0);
}

run().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(2);
});
