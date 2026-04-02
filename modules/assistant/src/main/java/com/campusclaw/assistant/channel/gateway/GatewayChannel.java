package com.campusclaw.assistant.channel.gateway;

import com.campusclaw.assistant.channel.Channel;
import com.campusclaw.assistant.channel.ChannelRegistry;
import com.campusclaw.assistant.channel.MessageSubmitter;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway Channel implementation.
 * Acts as a WebSocket server that chat tools can connect to directly.
 * Incoming messages are forwarded to the current interactive session's agent
 * via MessageSubmitter.submitMessage().
 */
@Component
@ConditionalOnProperty(prefix = "pi.assistant.gateway", name = "enabled", havingValue = "true")
public class GatewayChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(GatewayChannel.class);

    private final WebSocketGatewayProperties properties;
    private final ChannelRegistry channelRegistry;
    private final MessageSubmitter messageSubmitter; // LoopManager from coding-agent-cli, lazily resolved

    // channelId -> ChannelHandlerContext (for sending responses)
    private final Map<String, ChannelHandlerContext> sessionContexts = new ConcurrentHashMap<>();

    // sessionKey -> channelId (for routing responses)
    private final Map<String, String> sessionKeyToChannel = new ConcurrentHashMap<>();

    // channelId -> current sessionKey (for tracking active sessions)
    private final Map<String, String> channelToSessionKey = new ConcurrentHashMap<>();

    public GatewayChannel(
        WebSocketGatewayProperties properties,
        ChannelRegistry channelRegistry,
        @Lazy @Autowired(required = false) MessageSubmitter messageSubmitter
    ) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.messageSubmitter = messageSubmitter;
    }

    @PostConstruct
    public void register() {
        channelRegistry.register(this);
        log.info("Gateway channel registered with name: {}", properties.getName());
    }

    @Override
    public String getName() {
        return properties.getName();
    }

    @Override
    public void sendMessage(String message) {
        log.info("sendMessage called with message length: {}", message.length());
        for (Map.Entry<String, ChannelHandlerContext> entry : sessionContexts.entrySet()) {
            String channelId = entry.getKey();
            String sessionKey = channelToSessionKey.get(channelId);
            if (sessionKey != null) {
                sendMessageToSession(channelId, sessionKey, message);
            }
        }
    }

    /**
     * Register a WebSocket session.
     */
    public void registerSession(String channelId, ChannelHandlerContext ctx) {
        sessionContexts.put(channelId, ctx);
        log.debug("Registered session: {}", channelId);
    }

    /**
     * Remove a WebSocket session.
     */
    public void removeSession(String channelId) {
        sessionContexts.remove(channelId);
        String sessionKey = channelToSessionKey.remove(channelId);
        if (sessionKey != null) {
            sessionKeyToChannel.remove(sessionKey);
        }
        log.debug("Removed session: {}", channelId);
    }

    /**
     * Handle an incoming message from a client.
     * Forwards the message to the current interactive session's agent via LoopManager,
     * so the agent can process it using any available tools (CronTool, LoopTool, etc.).
     */
    public void handleIncomingMessage(String channelId, String sessionKey, String content) {
        log.info("Handling incoming message from channel {}, sessionKey {}: {} chars",
            channelId, sessionKey, content.length());

        // Associate sessionKey with channel
        sessionKeyToChannel.put(sessionKey, channelId);
        channelToSessionKey.put(channelId, sessionKey);

        // Forward to interactive session agent
        if (messageSubmitter != null) {
            boolean submitted = messageSubmitter.submitMessage(content);
            if (submitted) {
                log.info("Message forwarded to agent session");
                return;
            } else {
                log.warn("Failed to forward message to agent session (submit returned false)");
            }
        }

        // Fallback: send ACK if no session available
        String runId = UUID.randomUUID().toString();
        GatewayWebSocketHandler handler = getHandler(channelId);
        if (handler != null) {
            ChannelHandlerContext ctx = sessionContexts.get(channelId);
            handler.sendEvent(ctx, "chat", runId, sessionKey, "final",
                "[Gateway] No active session. Message not processed.");
        }
    }

    /**
     * Send a message to a specific session.
     */
    public void sendMessageToSession(String channelId, String sessionKey, String message) {
        ChannelHandlerContext ctx = sessionContexts.get(channelId);
        if (ctx == null) {
            log.warn("No context found for channel: {}", channelId);
            return;
        }

        GatewayWebSocketHandler handler = getHandler(channelId);
        if (handler == null) {
            log.warn("No handler found for channel: {}", channelId);
            return;
        }

        String runId = UUID.randomUUID().toString();
        handler.sendEvent(ctx, "chat", runId, sessionKey, "final", message);
        log.info("Sent message to session {}: {} chars", sessionKey, message.length());
    }

    /**
     * Send a streaming delta to a session.
     */
    public void sendDeltaToSession(String channelId, String sessionKey, String delta) {
        ChannelHandlerContext ctx = sessionContexts.get(channelId);
        if (ctx == null) return;

        GatewayWebSocketHandler handler = getHandler(channelId);
        if (handler == null) return;

        String runId = UUID.randomUUID().toString();
        handler.sendEvent(ctx, "chat", runId, sessionKey, "delta", delta);
    }

    private GatewayWebSocketHandler getHandler(String channelId) {
        ChannelHandlerContext ctx = sessionContexts.get(channelId);
        if (ctx == null) return null;
        try {
            return (GatewayWebSocketHandler) ctx.pipeline().get("messageHandler");
        } catch (Exception e) {
            log.error("Failed to get message handler: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the number of connected sessions.
     */
    public int getSessionCount() {
        return sessionContexts.size();
    }

    /**
     * Check if a session is connected.
     */
    public boolean hasSession(String channelId) {
        return sessionContexts.containsKey(channelId);
    }
}
