/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.mode.acp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEventListener;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration-style unit tests for {@link AcpMode}. We pipe ndJSON envelopes through the
 * package-private ctor so the JSON-RPC dispatch, response shaping and session event fan-out
 * are exercised end-to-end without forking a real process. {@link AgentSession} is mocked
 * via Mockito; the event listener it captures is invoked directly from the test thread so
 * the {@code session/update} notification branches are reproducible.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/09]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class AcpModeTest {

    private static final Logger log = LoggerFactory.getLogger(AcpModeTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int IO_TIMEOUT_MS = 2_000;

    private AgentSession session;
    private CountDownLatch listenerReady;
    private AtomicReference<AgentEventListener> listenerRef;
    private PipedOutputStream stdinWriter;
    private PipedInputStream stdin;
    private NotifyingByteArrayOutputStream stdout;
    private Thread runner;
    private AcpMode mode;

    @BeforeEach
    void setUpStreamsAndSession() throws IOException {
        session = Mockito.mock(AgentSession.class);
        listenerReady = new CountDownLatch(1);
        listenerRef = new AtomicReference<>();
        when(session.subscribe(any(AgentEventListener.class))).thenAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(0));
            listenerReady.countDown();
            return (Runnable) () -> {};
        });

        // newSession/abort default to no-op (void mocks)
        stdinWriter = new PipedOutputStream();
        stdin = new PipedInputStream(stdinWriter, 16 * 1024);
        stdout = new NotifyingByteArrayOutputStream();

        mode = new AcpMode(session, MAPPER, stdin, stdout);
    }

    @AfterEach
    void shutdown() throws Exception {
        if (stdinWriter != null) {
            try {
                stdinWriter.close();
            } catch (IOException e) {
                // best-effort cleanup — closing an already-closed stream is fine in this context
                log.debug("ignored: stdinWriter close in @AfterEach", e);
            }
        }
        if (runner != null) {
            runner.interrupt();
            runner.join(IO_TIMEOUT_MS);
        }
    }

    /**
     * Spawn run() on a background thread so the test can drive stdin/stdout from main.
     */
    private void startRunner() {
        runner = new Thread(mode::run, "acp-mode-test-runner");
        runner.setDaemon(true);
        runner.setUncaughtExceptionHandler((t, e) -> log.error("uncaught in acp-mode-test-runner", e));
        runner.start();
    }

    private void sendLine(String json) throws IOException {
        stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdinWriter.flush();
    }

    /**
     * Wait for a response envelope matching {@code id} (or any envelope if id is null).
     *
     * @param id the JSON-RPC request id to match, or null to match any response
     * @return the first matching envelope as parsed JSON, or null if none arrived in time
     * @throws Exception if interrupted or JSON parsing fails
     */
    private JsonNode awaitResponse(Long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            long observedVersion = stdout.version();
            for (JsonNode env : snapshotEnvelopes()) {
                if (env.has("id") && !env.get("id").isNull() && (env.has("result") || env.has("error"))) {
                    if (id == null || env.get("id").asLong() == id) {
                        return env;
                    }
                }
            }
            stdout.awaitWriteAfter(observedVersion, deadline);
        }
        return null;
    }

    private List<JsonNode> snapshotEnvelopes() throws Exception {
        java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
        for (String line : stdout.snapshot().split("\n")) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    @Nested
    class Initialize {

        @Test
        void respondsWithProtocolVersionAndCapabilities() throws Exception {
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            JsonNode response = awaitResponse(1L);

            assertThat(response).isNotNull();
            assertThat(response.get("result").get("protocolVersion").asInt()).isEqualTo(1);
            assertThat(response.get("result").get("agentInfo").get("name").asText())
                    .isEqualTo("campusclaw");
            assertThat(response.get("result")
                            .get("agentCapabilities")
                            .get("promptCapability")
                            .asBoolean())
                    .isTrue();
        }

        @Test
        void unknownMethodReturnsMethodNotFound() throws Exception {
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"does/not-exist\",\"params\":{}}");
            JsonNode response = awaitResponse(42L);

            assertThat(response).isNotNull();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(-32601);
        }
    }

    @Nested
    class NewSession {

        @Test
        void respondsWithGeneratedSessionId() throws Exception {
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{}}");
            JsonNode response = awaitResponse(2L);

            assertThat(response).isNotNull();
            String sessionId = response.get("result").get("sessionId").asText();
            assertThat(sessionId).startsWith("campusclaw-");
            verify(session).newSession();
        }

        @Test
        void newSessionExceptionStillReturnsSessionId() throws Exception {
            doThrow(new RuntimeException("boom")).when(session).newSession();
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/new\",\"params\":{}}");
            JsonNode response = awaitResponse(3L);

            // Exception in newSession is logged but a sessionId is still returned.
            assertThat(response).isNotNull();
            assertThat(response.get("result").get("sessionId").asText()).startsWith("campusclaw-");
        }
    }

    @Nested
    class Prompt {

        @Test
        void unknownSessionIdReturnsInvalidParams() throws Exception {
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"unknown\",\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");
            JsonNode response = awaitResponse(4L);

            assertThat(response).isNotNull();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
            assertThat(response.get("error").get("message").asText()).contains("unknown sessionId");
        }

        @Test
        void emptyPromptReturnsInvalidParams() throws Exception {
            startRunner();

            // create a session first
            sendLine("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/new\",\"params\":{}}");
            JsonNode newSession = awaitResponse(5L);
            String sessionId = newSession.get("result").get("sessionId").asText();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session/prompt\",\"params\":" + "{\"sessionId\":\""
                    + sessionId + "\",\"prompt\":[]}}");
            JsonNode response = awaitResponse(6L);

            assertThat(response).isNotNull();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
            assertThat(response.get("error").get("message").asText()).contains("prompt is empty");
        }

        @Test
        void promptDelegatesToSessionAndReplyWithEndTurn() throws Exception {
            CompletableFuture<Void> turn = new CompletableFuture<>();
            when(session.prompt(anyString())).thenReturn(turn);
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/new\",\"params\":{}}");
            JsonNode newSession = awaitResponse(7L);
            String sessionId = newSession.get("result").get("sessionId").asText();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");

            // Complete the turn — the response should now be sent.
            turn.complete(null);

            JsonNode response = awaitResponse(8L);
            assertThat(response).isNotNull();
            assertThat(response.get("result").get("stopReason").asText()).isEqualTo("end_turn");
            verify(session).prompt("hello");
        }

        @Test
        void sessionPromptThrowingReturnsInternalError() throws Exception {
            when(session.prompt(anyString())).thenThrow(new RuntimeException("session blew up"));
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/new\",\"params\":{}}");
            JsonNode newSession = awaitResponse(9L);
            String sessionId = newSession.get("result").get("sessionId").asText();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");
            JsonNode response = awaitResponse(10L);

            assertThat(response).isNotNull();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(-32603);
            assertThat(response.get("error").get("message").asText()).contains("session blew up");
        }

        @Test
        void promptCompletingExceptionallyReturnsInternalError() throws Exception {
            CompletableFuture<Void> turn = new CompletableFuture<>();
            when(session.prompt(anyString())).thenReturn(turn);
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"session/new\",\"params\":{}}");
            String sessionId = awaitResponse(11L).get("result").get("sessionId").asText();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");

            turn.completeExceptionally(new RuntimeException("agent crashed"));
            JsonNode response = awaitResponse(12L);

            assertThat(response).isNotNull();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(-32603);
            assertThat(response.get("error").get("message").asText()).contains("agent crashed");
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancelNotificationAbortsSession() throws Exception {
            doNothing().when(session).abort();
            startRunner();

            // Use a notification (no id) — handleNotification path.
            sendLine("{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\"," + "\"params\":{\"sessionId\":\"ignored\"}}");

            verify(session, timeout(IO_TIMEOUT_MS)).abort();
        }

        @Test
        void cancelAbortExceptionIsSwallowed() throws Exception {
            doThrow(new RuntimeException("abort fail")).when(session).abort();
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}");

            // No exception escapes — confirmed by reaching this point and abort() being invoked.
            verify(session, timeout(IO_TIMEOUT_MS)).abort();
        }
    }

    @Nested
    class SessionUpdates {

        @Test
        void messageUpdateEmitsAgentMessageChunkForNewSuffix() throws Exception {
            CompletableFuture<Void> turn = new CompletableFuture<>();
            when(session.prompt(anyString())).thenReturn(turn);
            startRunner();

            // boot session + start a prompt so currentSessionId is set
            sendLine("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"session/new\",\"params\":{}}");
            String sessionId = awaitResponse(20L).get("result").get("sessionId").asText();
            sendLine("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");

            // Listener was captured during run()'s subscribe(...) call. Wait for it.
            AgentEventListener listener = awaitListener();

            AssistantMessage m1 = assistantMessageOf("hello");
            listener.onEvent(new MessageUpdateEvent(m1, null));

            // Second update grows the buffer — only the delta is sent.
            AssistantMessage m2 = assistantMessageOf("hello world");
            listener.onEvent(new MessageUpdateEvent(m2, null));

            List<JsonNode> chunks = awaitSessionUpdates("agent_message_chunk", 2);

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
            String firstText = chunks.get(0)
                    .get("params")
                    .get("update")
                    .get("content")
                    .get("text")
                    .asText();
            String secondText = chunks.get(1)
                    .get("params")
                    .get("update")
                    .get("content")
                    .get("text")
                    .asText();
            assertThat(firstText).isEqualTo("hello");
            assertThat(secondText).isEqualTo(" world");

            turn.complete(null);
            awaitResponse(21L);
        }

        @Test
        void messageEndFlushesRemainderWhenLongerThanBufferedDelta() throws Exception {
            CompletableFuture<Void> turn = new CompletableFuture<>();
            when(session.prompt(anyString())).thenReturn(turn);
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"session/new\",\"params\":{}}");
            String sessionId = awaitResponse(30L).get("result").get("sessionId").asText();
            sendLine("{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");

            AgentEventListener listener = awaitListener();
            listener.onEvent(new MessageEndEvent(assistantMessageOf("final answer")));

            List<JsonNode> chunks = awaitSessionUpdates("agent_message_chunk", 1);
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0)
                            .get("params")
                            .get("update")
                            .get("content")
                            .get("text")
                            .asText())
                    .isEqualTo("final answer");

            turn.complete(null);
            awaitResponse(31L);
        }

        @Test
        void toolExecutionEventsEmitToolCallNotifications() throws Exception {
            CompletableFuture<Void> turn = new CompletableFuture<>();
            AgentEventListener listener = startSessionAndPrompt(40L, 41L, turn);

            listener.onEvent(new ToolExecutionStartEvent("tc-1", "bash", null));
            listener.onEvent(new ToolExecutionEndEvent("tc-1", "bash", "done", false));

            // AgentEndEvent goes through handleAgentEnd — currently a no-op but covers the branch.
            listener.onEvent(new AgentEndEvent(List.of()));

            List<JsonNode> toolUpdates = awaitSessionUpdates("tool_call", 2);

            assertToolCallSequence(toolUpdates);

            turn.complete(null);
            awaitResponse(41L);
        }

        @Test
        void messageUpdateBeforeSessionIdIsIgnored() throws Exception {
            startRunner();

            // Wait for run() to subscribe.
            AgentEventListener listener = awaitListener();

            // No session/new called yet — currentSessionId is null, listener should bail.
            listener.onEvent(new MessageUpdateEvent(assistantMessageOf("ignored"), null));

            // No envelopes should be written.
            assertThat(snapshotEnvelopes())
                    .filteredOn(e -> e.has("method")
                            && "session/update".equals(e.get("method").asText()))
                    .isEmpty();
        }

        /**
         * Boot a session and start a prompt on the ACP runner; returns the captured listener.
         *
         * @param newSessionRpcId JSON-RPC id used for the session/new envelope
         * @param promptRpcId JSON-RPC id used for the session/prompt envelope
         * @param turn the future returned from session.prompt(...) to control turn completion
         * @return the AgentEventListener subscribed by AcpMode.run()
         * @throws Exception if interrupted or stream IO fails
         */
        private AgentEventListener startSessionAndPrompt(
                long newSessionRpcId, long promptRpcId, CompletableFuture<Void> turn) throws Exception {
            when(session.prompt(anyString())).thenReturn(turn);
            startRunner();

            sendLine("{\"jsonrpc\":\"2.0\",\"id\":" + newSessionRpcId + ",\"method\":\"session/new\",\"params\":{}}");
            String sessionId = awaitResponse(newSessionRpcId)
                    .get("result")
                    .get("sessionId")
                    .asText();
            sendLine("{\"jsonrpc\":\"2.0\",\"id\":" + promptRpcId
                    + ",\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"" + sessionId + "\","
                    + "\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}");
            return awaitListener();
        }

        /**
         * Assert the canonical "tc-1 started → completed" tool_call envelope sequence.
         *
         * @param toolUpdates the captured tool_call notifications, in arrival order
         */
        private static void assertToolCallSequence(List<JsonNode> toolUpdates) {
            assertThat(toolUpdates).hasSize(2);
            assertThat(toolUpdates
                            .get(0)
                            .get("params")
                            .get("update")
                            .get("status")
                            .asText())
                    .isEqualTo("started");
            assertThat(toolUpdates
                            .get(0)
                            .get("params")
                            .get("update")
                            .get("toolCallId")
                            .asText())
                    .isEqualTo("tc-1");
            assertThat(toolUpdates
                            .get(0)
                            .get("params")
                            .get("update")
                            .get("name")
                            .asText())
                    .isEqualTo("bash");
            assertThat(toolUpdates
                            .get(1)
                            .get("params")
                            .get("update")
                            .get("status")
                            .asText())
                    .isEqualTo("completed");
        }
    }

    // ---- helpers ----

    private AgentEventListener awaitListener() throws InterruptedException {
        if (!listenerReady.await(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("session.subscribe was not invoked in time");
        }
        AgentEventListener listener = listenerRef.get();
        if (listener == null) {
            throw new IllegalStateException("session.subscribe did not capture a listener");
        }
        return listener;
    }

    private List<JsonNode> awaitSessionUpdates(String updateType, int minCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            long observedVersion = stdout.version();
            List<JsonNode> updates = sessionUpdates(updateType);
            if (updates.size() >= minCount) {
                return updates;
            }
            stdout.awaitWriteAfter(observedVersion, deadline);
        }
        return sessionUpdates(updateType);
    }

    private List<JsonNode> sessionUpdates(String updateType) throws Exception {
        return snapshotEnvelopes().stream()
                .filter(e -> e.has("method")
                        && "session/update".equals(e.get("method").asText()))
                .filter(e -> updateType.equals(
                        e.get("params").get("update").get("sessionUpdate").asText()))
                .toList();
    }

    private static AssistantMessage assistantMessageOf(String text) {
        return new AssistantMessage(
                List.<ContentBlock>of(new TextContent(text)),
                "test-api",
                "test-provider",
                "test-model",
                null,
                null,
                null,
                null,
                0L);
    }

    private static final class NotifyingByteArrayOutputStream extends ByteArrayOutputStream {

        private long version;

        @Override
        public synchronized void write(int b) {
            super.write(b);
            version++;
            notifyAll();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            super.write(b, off, len);
            version++;
            notifyAll();
        }

        private synchronized String snapshot() {
            return toString(StandardCharsets.UTF_8);
        }

        private synchronized long version() {
            return version;
        }

        private synchronized long awaitWriteAfter(long observedVersion, long deadlineNanos)
                throws InterruptedException {
            while (version == observedVersion) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return version;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return version;
        }
    }
}
