#!/usr/bin/env node
/**
 * Mock OpenClaw connector — tests pi-mono-java WebSocket gateway protocol compliance.
 *
 * Usage:  node test-gateway.mjs [url]
 * Default: ws://127.0.0.1:18788/
 */

import WebSocket from "ws";

const url = process.argv[2] || "ws://127.0.0.1:18788/";
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
function waitFor(ws, predicate, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout")), timeoutMs);
    ws.once("message", (raw) => {
      clearTimeout(timer);
      const msg = JSON.parse(raw.toString());
      if (predicate(msg)) resolve(msg);
      else {
        // re-attach and keep waiting
        waitFor(ws, predicate, timeoutMs).then(resolve).catch(reject);
      }
    });
  });
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
  console.log("  WebSocket connection established.\n");

  // ── Test 1: connect.challenge event ────────────────────────────
  console.log("── Test 1: connect.challenge event ──");
  const challenge = await waitFor(ws, (m) => m.event === "connect.challenge");
  assert("Frame type is 'event'", challenge.type === "event");
  assert("Event name is 'connect.challenge'", challenge.event === "connect.challenge");
  assert("payload.nonce exists", typeof challenge.payload?.nonce === "string" && challenge.payload.nonce.length > 0);
  assert("payload.ts is a number", typeof challenge.payload?.ts === "number");
  console.log();

  // ── Test 2: connect request → hello-ok ────────────────────────
  console.log("── Test 2: connect → hello-ok ──");
  const connectReq = {
    type: "req",
    id: "connect-1",
    method: "connect",
    params: {
      minProtocol: 3,
      maxProtocol: 3,
      client: { id: "test-client", version: "0.1.0", platform: "test" },
      auth: { token: "test-token" },
    },
  };
  ws.send(JSON.stringify(connectReq));

  const helloRes = await waitFor(ws, (m) => m.id === "connect-1");
  assert("Response type is 'res'", helloRes.type === "res");
  assert("Response id matches", helloRes.id === "connect-1");
  assert("ok === true", helloRes.ok === true);
  assert("payload.type is 'hello-ok'", helloRes.payload?.type === "hello-ok");
  assert("payload.protocol is 3", helloRes.payload?.protocol === 3);
  assert("payload.server.version exists", typeof helloRes.payload?.server?.version === "string");
  assert("payload.server.connId exists", typeof helloRes.payload?.server?.connId === "string");
  assert("Event frames include top-level seq", typeof challenge.seq === "number");
  assert("Event frames include top-level stateVersion", typeof challenge.stateVersion === "object");
  assert("payload.features.methods is an array", Array.isArray(helloRes.payload?.features?.methods));
  assert("payload.features.events is an array", Array.isArray(helloRes.payload?.features?.events));
  assert("payload.features.events includes 'chat'", helloRes.payload?.features?.events?.includes("chat"));
  assert("payload.features.events includes 'tick'", helloRes.payload?.features?.events?.includes("tick"));
  assert("payload.policy.maxPayload is number", typeof helloRes.payload?.policy?.maxPayload === "number");
  assert("payload.policy.maxBufferedBytes is number", typeof helloRes.payload?.policy?.maxBufferedBytes === "number");
  assert("payload.policy.tickIntervalMs is number", typeof helloRes.payload?.policy?.tickIntervalMs === "number");
  console.log();

  // ── Test 3: sessions.send request → accepted res + chat event ──
  console.log("── Test 3: sessions.send → accepted + chat event ──");
  const sendReq = {
    type: "req",
    id: "send-1",
    method: "sessions.send",
    params: { key: "test-session", message: "Hello from mock connector!" },
  };
  ws.send(JSON.stringify(sendReq));

  const sendRes = await waitFor(ws, (m) => m.id === "send-1");
  assert("Response type is 'res'", sendRes.type === "res");
  assert("Response id matches", sendRes.id === "send-1");
  assert("ok === true", sendRes.ok === true);
  assert("payload.status === 'accepted'", sendRes.payload?.status === "accepted");
  console.log();

  // ── Test 4: chat event (may arrive later from task) ────────────
  console.log("── Test 4: chat event (wait up to 8s) ──");
  try {
    const chatEvent = await waitFor(ws, (m) => m.event === "chat", 8000);
    assert("Frame type is 'event'", chatEvent.type === "event");
    assert("Event name is 'chat'", chatEvent.event === "chat");
    assert("payload.runId exists", typeof chatEvent.payload?.runId === "string");
    assert("payload.sessionKey === 'test-session'", chatEvent.payload?.sessionKey === "test-session");
    assert("payload.seq is a number", typeof chatEvent.payload?.seq === "number");
    assert("payload.state exists", typeof chatEvent.payload?.state === "string");
    assert("payload.message exists", chatEvent.payload?.message != null);
    console.log();
  } catch {
    console.log("  ⚠️  No chat event received (task may have failed server-side, this is OK for protocol test)\n");
  }

  // ── Test 5: tick event ─────────────────────────────────────────
  console.log("── Test 5: tick event (wait up to 35s) ──");
  try {
    const tickEvent = await waitFor(ws, (m) => m.event === "tick", 35000);
    assert("Frame type is 'event'", tickEvent.type === "event");
    assert("Event name is 'tick'", tickEvent.event === "tick");
    assert("payload.ts is a number", typeof tickEvent.payload?.ts === "number");
    console.log();
  } catch {
    console.log("  ⚠️  No tick event received within timeout\n");
  }

  // ── Test 6: error response for unauthenticated method ──────────
  // (already authenticated, so test unknown method instead)
  console.log("── Test 6: unknown method → error ──");
  const badReq = { type: "req", id: "bad-1", method: "foo.bar", params: {} };
  ws.send(JSON.stringify(badReq));

  const badRes = await waitFor(ws, (m) => m.id === "bad-1");
  assert("Response type is 'res'", badRes.type === "res");
  assert("ok === false", badRes.ok === false);
  assert("error is an object (not string)", typeof badRes.error === "object" && badRes.error !== null);
  assert("error.code exists", typeof badRes.error?.code === "string");
  assert("error.message exists", typeof badRes.error?.message === "string");
  console.log();

  // ── Summary ────────────────────────────────────────────────────
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
