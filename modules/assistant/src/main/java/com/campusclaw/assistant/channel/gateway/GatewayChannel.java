package com.campusclaw.assistant.channel.gateway;

import com.campusclaw.assistant.channel.Channel;
import com.campusclaw.assistant.channel.ChannelRegistry;
import com.campusclaw.assistant.task.Task;
import com.campusclaw.assistant.task.TaskManager;
import com.campusclaw.assistant.task.TaskRepository;
import com.campusclaw.assistant.task.TaskStatus;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway Channel implementation.
 * Acts as a WebSocket server that chat tools can connect to directly.
 */
@Component
@ConditionalOnProperty(prefix = "pi.assistant.gateway", name = "enabled", havingValue = "true")
public class GatewayChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(GatewayChannel.class);

    private final WebSocketGatewayProperties properties;
    private final ChannelRegistry channelRegistry;
    private final TaskRepository taskRepository;
    private final TaskManager taskManager;

    // channelId -> ChannelHandlerContext (for sending responses)
    private final Map<String, ChannelHandlerContext> sessionContexts = new ConcurrentHashMap<>();

    // sessionKey -> channelId (for routing responses)
    private final Map<String, String> sessionKeyToChannel = new ConcurrentHashMap<>();

    // channelId -> current sessionKey (for tracking active sessions)
    private final Map<String, String> channelToSessionKey = new ConcurrentHashMap<>();

    public GatewayChannel(
        WebSocketGatewayProperties properties,
        ChannelRegistry channelRegistry,
        TaskRepository taskRepository,
        TaskManager taskManager
    ) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.taskRepository = taskRepository;
        this.taskManager = taskManager;
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
        // This is called by TaskManager after task completion
        // We need to find the appropriate session to send to

        log.info("sendMessage called with message length: {}", message.length());

        // Find active sessions and send to them
        // This could be enhanced to target specific sessions based on task metadata
        for (Map.Entry<String, ChannelHandlerContext> entry : sessionContexts.entrySet()) {
            String channelId = entry.getKey();
            ChannelHandlerContext ctx = entry.getValue();

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
     */
    public void handleIncomingMessage(String channelId, String sessionKey, String content) {
        log.info("Handling incoming message from channel {}, sessionKey {}: {} chars",
            channelId, sessionKey, content.length());

        // Associate sessionKey with channel
        sessionKeyToChannel.put(sessionKey, channelId);
        channelToSessionKey.put(channelId, sessionKey);

        // Create a Task for this message
        String taskId = UUID.randomUUID().toString();
        String conversationId = sessionKey; // Use sessionKey as conversationId

        Task task = new Task(
            taskId,
            conversationId,
            content,
            TaskStatus.TODO,
            null,
            getName(),
            Instant.now(),
            Instant.now()
        );

        taskRepository.save(task);
        log.info("Created task {} for conversation {}", taskId, conversationId);

        // Execute the task
        taskManager.executeTask(taskId)
            .thenAccept(result -> {
                log.info("Task {} completed, result length: {}", taskId, result.length());
                // Send result back to the session
                sendMessageToSession(channelId, sessionKey, result);
            })
            .exceptionally(e -> {
                log.error("Task {} failed: {}", taskId, e.getMessage());
                sendMessageToSession(channelId, sessionKey, "Error: " + e.getMessage());
                return null;
            });
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

        // Get the handler to send events
        GatewayWebSocketHandler handler = getHandler(ctx);
        if (handler == null) {
            log.warn("No handler found for channel: {}", channelId);
            return;
        }

        // Send as event frame (chat event)
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

        GatewayWebSocketHandler handler = getHandler(ctx);
        if (handler == null) return;

        String runId = UUID.randomUUID().toString();
        handler.sendEvent(ctx, "chat", runId, sessionKey, "delta", delta);
    }

    private GatewayWebSocketHandler getHandler(ChannelHandlerContext ctx) {
        // The handler is added to the pipeline in WebSocketGatewayConfig
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