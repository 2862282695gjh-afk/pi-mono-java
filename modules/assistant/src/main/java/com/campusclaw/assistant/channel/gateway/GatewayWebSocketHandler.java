package com.campusclaw.assistant.channel.gateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles WebSocket messages from connected chat clients.
 * Implements OpenClaw protocol for message framing.
 */
public class GatewayWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);

    private final WebSocketGatewayProperties properties;
    private final GatewayChannel gatewayChannel;
    private final Map<String, ChannelHandlerContext> sessions = new ConcurrentHashMap<>();

    // Track authenticated sessions
    private final Map<String, Boolean> authenticatedSessions = new ConcurrentHashMap<>();

    public GatewayWebSocketHandler(WebSocketGatewayProperties properties, GatewayChannel gatewayChannel) {
        this.properties = properties;
        this.gatewayChannel = gatewayChannel;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            log.info("WebSocket handshake completed for channel: {}", ctx.channel().id());
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        String channelId = ctx.channel().id().asShortText();

        log.debug("Received message from {}: {} chars", channelId, text.length());

        try {
            // Parse message to determine type
            if (text.contains("\"type\":\"connect\"")) {
                handleConnect(ctx, text);
            } else if (text.contains("\"type\":\"req\"")) {
                handleRequest(ctx, text);
            } else {
                log.warn("Unknown message type from {}: {}", channelId,
                    text.substring(0, Math.min(100, text.length())));
            }
        } catch (Exception e) {
            log.error("Error processing message from {}: {}", channelId, e.getMessage());
            sendHelloError(ctx, "parseError", e.getMessage());
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        sessions.put(channelId, ctx);
        log.info("Client connected: {}", channelId);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        sessions.remove(channelId);
        authenticatedSessions.remove(channelId);
        gatewayChannel.removeSession(channelId);
        log.info("Client disconnected: {}", channelId);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("WebSocket error for {}: {}", ctx.channel().id().asShortText(), cause.getMessage());
        ctx.close();
    }

    private void handleConnect(ChannelHandlerContext ctx, String text) {
        try {
            // Parse ConnectParams
            // Expected format: {"type":"connect", "minProtocol":3, "maxProtocol":3, "client":{...}, "auth":{"token":"..."}}
            String token = extractToken(text);

            // Validate token
            if (properties.getToken() != null && !properties.getToken().isEmpty()) {
                if (token == null || !properties.getToken().equals(token)) {
                    log.warn("Invalid token from {}", ctx.channel().id().asShortText());
                    sendHelloError(ctx, "authFailed", "Invalid or missing token");
                    ctx.close();
                    return;
                }
            }

            // Mark session as authenticated
            String channelId = ctx.channel().id().asShortText();
            authenticatedSessions.put(channelId, true);
            gatewayChannel.registerSession(channelId, ctx);

            // Send HelloOk
            sendHelloOk(ctx);

            log.info("Client {} authenticated successfully", channelId);
        } catch (Exception e) {
            log.error("Error handling connect: {}", e.getMessage());
            sendHelloError(ctx, "connectError", e.getMessage());
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, String text) {
        String channelId = ctx.channel().id().asShortText();

        // Check if authenticated
        if (!authenticatedSessions.containsKey(channelId)) {
            log.warn("Unauthenticated request from {}", channelId);
            sendResponse(ctx, extractRequestId(text), false, null, "Not authenticated");
            return;
        }

        try {
            // Parse request
            String requestId = extractRequestId(text);
            String method = extractMethod(text);
            String sessionKey = extractSessionKey(text);
            String messageContent = extractMessageContent(text);

            log.info("Request from {}: method={}, sessionKey={}, contentLength={}",
                channelId, method, sessionKey, messageContent != null ? messageContent.length() : 0);

            // Handle sessions.send
            if ("sessions.send".equals(method)) {
                if (sessionKey != null && messageContent != null) {
                    // Send response first (acknowledgment)
                    sendResponse(ctx, requestId, true, Map.of("status", "accepted"), null);

                    // Create task for message processing
                    gatewayChannel.handleIncomingMessage(channelId, sessionKey, messageContent);
                } else {
                    sendResponse(ctx, requestId, false, null, "Missing key or message");
                }
            } else if ("policy.tick".equals(method)) {
                // Heartbeat tick - just respond
                sendResponse(ctx, requestId, true, Map.of("tick", true), null);
            } else {
                log.warn("Unknown method: {}", method);
                sendResponse(ctx, requestId, false, null, "Unknown method: " + method);
            }
        } catch (Exception e) {
            log.error("Error handling request: {}", e.getMessage());
            sendResponse(ctx, extractRequestId(text), false, null, e.getMessage());
        }
    }

    private void sendHelloOk(ChannelHandlerContext ctx) {
        String json = String.format(
            "{\"type\":\"helloOk\",\"protocol\":3,\"policy\":{\"tickIntervalMs\":%d}}",
            properties.getTickIntervalMs()
        );
        ctx.writeAndFlush(new TextWebSocketFrame(json));
        log.debug("Sent HelloOk to {}", ctx.channel().id().asShortText());
    }

    private void sendHelloError(ChannelHandlerContext ctx, String code, String message) {
        String json = String.format(
            "{\"type\":\"helloError\",\"code\":\"%s\",\"message\":\"%s\"}",
            escapeJson(code), escapeJson(message)
        );
        ctx.writeAndFlush(new TextWebSocketFrame(json));
    }

    private void sendResponse(ChannelHandlerContext ctx, String requestId, boolean ok, Object payload, String error) {
        StringBuilder json = new StringBuilder();
        json.append("{\"type\":\"res\",\"id\":\"").append(escapeJson(requestId)).append("\"");
        json.append(",\"ok\":").append(ok);

        if (payload != null) {
            // Simple payload handling
            if (payload instanceof Map) {
                json.append(",\"payload\":{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) payload).entrySet()) {
                    if (!first) json.append(",");
                    json.append("\"").append(entry.getKey()).append("\":");
                    if (entry.getValue() instanceof String) {
                        json.append("\"").append(escapeJson(entry.getValue().toString())).append("\"");
                    } else {
                        json.append(entry.getValue());
                    }
                    first = false;
                }
                json.append("}");
            } else {
                json.append(",\"payload\":\"").append(escapeJson(payload.toString())).append("\"");
            }
        }

        if (error != null) {
            json.append(",\"error\":\"").append(escapeJson(error)).append("\"");
        }

        json.append("}");
        ctx.writeAndFlush(new TextWebSocketFrame(json.toString()));
    }

    /**
     * Send an event frame to a client.
     */
    public void sendEvent(ChannelHandlerContext ctx, String event, String runId, String sessionKey,
                          String state, String content) {
        String messageId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        StringBuilder json = new StringBuilder();
        json.append("{\"type\":\"event\",\"event\":\"").append(escapeJson(event)).append("\"");
        json.append(",\"payload\":{\"runId\":\"").append(escapeJson(runId)).append("\"");
        json.append(",\"sessionKey\":\"").append(escapeJson(sessionKey)).append("\"");
        json.append(",\"state\":\"").append(escapeJson(state)).append("\"");
        json.append(",\"message\":{\"id\":\"").append(escapeJson(messageId)).append("\"");
        json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
        json.append(",\"senderId\":\"assistant\"");
        json.append(",\"senderName\":\"Assistant\"");
        json.append(",\"timestamp\":").append(timestamp);
        json.append(",\"attachments\":[]}}}");

        ctx.writeAndFlush(new TextWebSocketFrame(json.toString()));
        log.debug("Sent event to {}: event={}, state={}", ctx.channel().id().asShortText(), event, state);
    }

    /**
     * Get session by channel ID.
     */
    public ChannelHandlerContext getSession(String channelId) {
        return sessions.get(channelId);
    }

    // Simple JSON extraction methods (for performance, avoid full parsing)
    private String extractToken(String text) {
        return extractStringValue(text, "token");
    }

    private String extractRequestId(String text) {
        return extractStringValue(text, "id");
    }

    private String extractMethod(String text) {
        return extractStringValue(text, "method");
    }

    private String extractSessionKey(String text) {
        return extractStringValue(text, "key");
    }

    private String extractMessageContent(String text) {
        // Look for "content":"..." in the message field
        int contentIdx = text.indexOf("\"content\"");
        if (contentIdx == -1) return null;

        int quoteStart = text.indexOf("\"", contentIdx + 9);
        if (quoteStart == -1) return null;

        int quoteEnd = text.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;

        return text.substring(quoteStart + 1, quoteEnd);
    }

    private String extractStringValue(String text, String key) {
        String pattern = "\"" + key + "\"";
        int idx = text.indexOf(pattern);
        if (idx == -1) return null;

        int quoteStart = text.indexOf("\"", idx + pattern.length());
        if (quoteStart == -1) return null;

        int quoteEnd = text.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;

        return text.substring(quoteStart + 1, quoteEnd);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}