/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.mode.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.util.LoggingUncaughtExceptionHandler;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Message;
import com.campusclaw.codingagent.config.AppPaths;
import com.campusclaw.codingagent.prompt.SystemPromptBuilder;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.DelegationState;
import com.campusclaw.codingagent.runtime.DelegationWiring;
import com.campusclaw.codingagent.runtime.LocalAgentDispatcher;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.session.AgentSession;
import com.campusclaw.codingagent.session.SessionConfig;
import com.campusclaw.codingagent.session.SessionManager;
import com.campusclaw.codingagent.settings.Settings;
import com.campusclaw.codingagent.settings.SettingsManager;
import com.campusclaw.codingagent.skill.SandboxSkillParser;
import com.campusclaw.codingagent.skill.SkillExpander;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.campusclaw.codingagent.tool.catalog.ToolCatalog;
import com.campusclaw.codingagent.tool.catalog.ToolRefreshRequest;
import com.campusclaw.codingagent.tool.catalog.ToolSelection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages multiple {@link AgentSession} instances keyed by conversation ID.
 * Sessions are created on demand and evicted after an idle timeout.
 *
 * <p>When persistence is enabled, each session is backed by a {@link SessionManager}
 * that writes JSONL to {@code ~/.campusclaw/agent/sessions/--<encoded-cwd>--/<id>.jsonl}.
 * The JSONL filename equals the conversation ID, so reconnects with the same
 * {@code conversation_id} after eviction or process restart resume from disk.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class SessionPool {

    private static final Logger log = LoggerFactory.getLogger(SessionPool.class);
    private static final long IDLE_TIMEOUT_MINUTES = 30L;
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final CampusClawAiService aiService;
    private final ModelRegistry modelRegistry;
    private final SystemPromptBuilder promptBuilder;
    private final List<AgentTool> tools;
    private final ToolCatalog toolCatalog;
    private volatile ToolSelection toolSelection;
    private final Function<Settings.ToolsSettings, ToolSelection> toolSelectionResolver;
    private final SessionConfig baseConfig;
    private final SandboxSkillParser sandboxParser;
    private final boolean useSandbox;
    private final boolean persistenceEnabled;
    private final SettingsManager settingsManager;
    private final AgentRuntimeManager agentRuntimeManager;
    private final String defaultAgentId;
    private final LocalAgentDispatcher delegationDispatcher;

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<AgentSession>> inFlightCreations =
            new ConcurrentHashMap<>();
    private final Object toolReloadLock = new Object();
    private final ScheduledExecutorService cleaner;
    private com.campusclaw.agent.subagent.SubAgentRegistry subAgentRegistry;

    record Entry(AgentSession session, long lastAccess) {}

    private record ReloadCounts(int deferred, int failed) {}

    private record ReloadedCatalog(long version, List<String> diagnostics, List<String> visibleToolNames) {}

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig) {
        this(aiService, modelRegistry, promptBuilder, tools, baseConfig, null, false, true);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox) {
        this(aiService, modelRegistry, promptBuilder, tools, baseConfig, sandboxParser, useSandbox, true);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                null,
                ToolSelection.all(),
                baseConfig,
                sandboxParser,
                useSandbox,
                persistenceEnabled,
                null);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            ToolCatalog toolCatalog,
            ToolSelection toolSelection,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                toolCatalog,
                toolSelection,
                baseConfig,
                sandboxParser,
                useSandbox,
                persistenceEnabled,
                null);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            ToolCatalog toolCatalog,
            ToolSelection toolSelection,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled,
            SettingsManager settingsManager) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                toolCatalog,
                toolSelection,
                baseConfig,
                sandboxParser,
                useSandbox,
                persistenceEnabled,
                settingsManager,
                null,
                null);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            ToolCatalog toolCatalog,
            ToolSelection toolSelection,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled,
            SettingsManager settingsManager,
            AgentRuntimeManager agentRuntimeManager,
            String defaultAgentId) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                toolCatalog,
                toolSelection,
                baseConfig,
                sandboxParser,
                useSandbox,
                persistenceEnabled,
                settingsManager,
                agentRuntimeManager,
                defaultAgentId,
                fixedSelectionResolver(toolSelection));
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            ToolCatalog toolCatalog,
            ToolSelection toolSelection,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled,
            SettingsManager settingsManager,
            AgentRuntimeManager agentRuntimeManager,
            String defaultAgentId,
            Function<Settings.ToolsSettings, ToolSelection> toolSelectionResolver) {
        this(
                aiService,
                modelRegistry,
                promptBuilder,
                tools,
                toolCatalog,
                toolSelection,
                baseConfig,
                sandboxParser,
                useSandbox,
                persistenceEnabled,
                settingsManager,
                agentRuntimeManager,
                defaultAgentId,
                toolSelectionResolver,
                null);
    }

    public SessionPool(
            CampusClawAiService aiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            List<AgentTool> tools,
            ToolCatalog toolCatalog,
            ToolSelection toolSelection,
            SessionConfig baseConfig,
            SandboxSkillParser sandboxParser,
            boolean useSandbox,
            boolean persistenceEnabled,
            SettingsManager settingsManager,
            AgentRuntimeManager agentRuntimeManager,
            String defaultAgentId,
            Function<Settings.ToolsSettings, ToolSelection> toolSelectionResolver,
            LocalAgentDispatcher delegationDispatcher) {
        this.aiService = aiService;
        this.modelRegistry = modelRegistry;
        this.promptBuilder = promptBuilder;
        this.tools = tools;
        this.toolCatalog = toolCatalog;
        this.toolSelection = toolSelection != null ? toolSelection : ToolSelection.all();
        this.toolSelectionResolver =
                toolSelectionResolver != null ? toolSelectionResolver : fixedSelectionResolver(this.toolSelection);
        this.baseConfig = baseConfig;
        this.sandboxParser = sandboxParser;
        this.useSandbox = useSandbox;
        this.persistenceEnabled = persistenceEnabled;
        this.settingsManager = settingsManager;
        this.agentRuntimeManager = agentRuntimeManager;
        this.defaultAgentId = defaultAgentId;
        this.delegationDispatcher = delegationDispatcher;

        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "session-pool-cleaner");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.INSTANCE);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictIdle, IDLE_TIMEOUT_MINUTES, 5, TimeUnit.MINUTES);
    }

    /**
     * Returns an existing session for the given conversation ID, or creates a new one.
     *
     * <p>When persistence is enabled and the conversation ID maps to an existing
     * JSONL file on disk, the session is restored from disk before being returned.
     * If conversationId is null, a new session is created with a generated ID.
     *
     * @param conversationId the conversation ID to look up; {@code null} or blank to generate a new one
     * @return the resolved session reference
     */
    public SessionRef getOrCreate(String conversationId) {
        return getOrCreate(defaultAgentId, conversationId);
    }

    /**
     * Returns an existing session for an Agent/conversation pair, or creates one.
     *
     * <p>Session creation (which may perform remote Agent preparation with
     * multi-second I/O) runs outside the sessions map lock; concurrent creation
     * for the same key is deduplicated through an in-flight future, while
     * creation for different keys proceeds in parallel.</p>
     *
     * @param agentId selected managed Agent ID; blank uses the server default/legacy session
     * @param conversationId conversation ID; blank generates a new ID
     * @return resolved session reference
     */
    public SessionRef getOrCreate(String agentId, String conversationId) {
        String effectiveAgentId = normalizeAgentId(agentId);
        String id = (conversationId != null && !conversationId.isBlank())
                ? conversationId
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        validateConversationId(id);
        String key = sessionKey(effectiveAgentId, id);
        Entry existing = sessions.get(key);
        if (existing != null) {
            sessions.computeIfPresent(key, (ignored, current) -> new Entry(current.session(), now()));
            return new SessionRef(id, existing.session());
        }
        return createOrAwaitSession(key, effectiveAgentId, id);
    }

    private SessionRef createOrAwaitSession(String key, String agentId, String conversationId) {
        CompletableFuture<AgentSession> creation = new CompletableFuture<>();
        CompletableFuture<AgentSession> inFlight = inFlightCreations.putIfAbsent(key, creation);
        if (inFlight != null) {
            return new SessionRef(conversationId, awaitSession(key, inFlight));
        }
        try {
            AgentSession created = createSessionWithPersistence(agentId, conversationId);
            sessions.put(key, new Entry(created, now()));
            creation.complete(created);
            return new SessionRef(conversationId, created);
        } catch (RuntimeException e) {
            creation.completeExceptionally(e);
            throw e;
        } finally {
            inFlightCreations.remove(key, creation);
        }
    }

    private AgentSession awaitSession(String key, CompletableFuture<AgentSession> inFlight) {
        AgentSession session;
        try {
            session = inFlight.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
        sessions.computeIfPresent(key, (ignored, current) -> new Entry(current.session(), now()));
        return session;
    }

    /**
     * Removes a conversation and its session.
     *
     * @param conversationId the conversation ID to remove
     * @return true if the conversation existed and was removed
     */
    public boolean remove(String conversationId) {
        return remove(defaultAgentId, conversationId);
    }

    /**
     * Removes one Agent-scoped conversation.
     *
     * @param agentId selected Agent ID, or {@code null} for the legacy/default Agent
     * @param conversationId conversation ID to remove
     * @return true if the scoped conversation existed
     */
    public boolean remove(String agentId, String conversationId) {
        validateConversationId(conversationId);
        String effectiveAgentId = normalizeAgentId(agentId);
        Entry removed = sessions.remove(sessionKey(effectiveAgentId, conversationId));
        if (removed == null) {
            return false;
        }
        closeQuietly(removed.session());
        log.info("Removed conversation {} for Agent {}", conversationId, effectiveAgentId);
        return true;
    }

    /**
     * Re-keys a pool entry under a new conversation ID. Used by the WS
     * {@code new_session} command, which rotates the conversation ID so the
     * fresh history goes to its own JSONL file.
     *
     * @param oldId the oldId
     * @param newId the newId
     */
    public void rekey(String oldId, String newId) {
        rekey(defaultAgentId, oldId, newId);
    }

    /**
     * Re-keys one Agent-scoped conversation.
     *
     * @param agentId selected Agent ID
     * @param oldId previous conversation ID
     * @param newId replacement conversation ID
     */
    public void rekey(String agentId, String oldId, String newId) {
        validateConversationId(oldId);
        validateConversationId(newId);
        String effectiveAgentId = normalizeAgentId(agentId);
        var entry = sessions.remove(sessionKey(effectiveAgentId, oldId));
        if (entry != null) {
            sessions.put(sessionKey(effectiveAgentId, newId), new Entry(entry.session(), now()));
        }
    }

    /**
     * Returns the number of active sessions.
     *
     * @return the result
     */
    public int size() {
        return sessions.size();
    }

    /**
     * Resolves the persistence cwd for an Agent-scoped conversation listing.
     * Read-only: never fetches from CampusMate and never materializes a
     * directory; Agents without a complete local snapshot fall back to the
     * base cwd.
     *
     * @param agentId selected Agent ID, or {@code null} for the default/legacy runtime
     * @return normalized session cwd
     */
    Path conversationCwd(String agentId) {
        PreparedAgentRuntime prepared = prepareCachedRuntime(normalizeAgentId(agentId));
        Path configured = prepared != null ? prepared.agentRoot() : baseConfig.cwd();
        return (configured != null ? configured : Path.of(System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
    }

    private PreparedAgentRuntime prepareCachedRuntime(String agentId) {
        if (agentId == null) {
            return null;
        }
        if (agentRuntimeManager == null) {
            throw new IllegalStateException("Managed Agent runtime is not configured");
        }
        return agentRuntimeManager.prepareCached(agentId);
    }

    /**
     * Returns diagnostic information for the active tool catalog snapshot.
     *
     * @return response payload for the server API
     */
    public Map<String, Object> toolStatus() {
        if (toolCatalog == null) {
            return Map.of(
                    "status",
                    "disabled",
                    "activeSessions",
                    size(),
                    "tools",
                    tools.stream().map(AgentTool::name).toList());
        }
        synchronized (toolCatalog) {
            var snapshot = toolCatalog.snapshot();
            return Map.of(
                    "status",
                    "ok",
                    "version",
                    snapshot.version(),
                    "degraded",
                    snapshot.degraded(),
                    "diagnostics",
                    snapshot.diagnostics(),
                    "activeSessions",
                    size(),
                    "tools",
                    toolCatalog.resolve(toolSelection).stream()
                            .map(AgentTool::name)
                            .toList());
        }
    }

    /**
     * Refreshes catalog-backed tools and updates active sessions.
     *
     * @return response payload for the server API
     */
    public Map<String, Object> reloadTools() {
        synchronized (toolReloadLock) {
            return reloadToolsSerially();
        }
    }

    private Map<String, Object> reloadToolsSerially() {
        if (toolCatalog == null) {
            return Map.of("status", "disabled", "message", "Tool catalog is not available");
        }
        var toolsSettings = currentToolsSettings();
        ToolSelection effectiveSelection = resolveSelection(toolsSettings);
        toolSelection = effectiveSelection;
        var baseRequest = new ToolRefreshRequest(baseConfig.cwd());
        synchronized (toolCatalog) {
            toolCatalog.refresh(baseRequest);
        }
        ReloadCounts counts;
        ReloadedCatalog reloadedCatalog;
        try {
            counts = reloadActiveSessions(effectiveSelection);
        } finally {
            reloadedCatalog = restoreBaseCatalog(baseRequest, effectiveSelection);
        }
        return Map.of(
                "status",
                counts.failed() == 0 ? "ok" : "partial",
                "version",
                reloadedCatalog.version(),
                "diagnostics",
                reloadedCatalog.diagnostics(),
                "deferredSessions",
                counts.deferred(),
                "failedSessions",
                counts.failed(),
                "tools",
                reloadedCatalog.visibleToolNames());
    }

    private ToolSelection resolveSelection(Settings.ToolsSettings toolsSettings) {
        ToolSelection resolved = toolSelectionResolver.apply(toolsSettings);
        return resolved != null ? resolved : ToolSelection.all();
    }

    private ReloadCounts reloadActiveSessions(ToolSelection selection) {
        int deferred = 0;
        int failed = 0;
        for (Entry entry : sessions.values()) {
            try {
                entry.session().setToolSelection(selection);
                if (!entry.session().reloadToolsWhenIdle()) {
                    deferred++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Failed to reload tools for an active session", e);
            }
        }
        return new ReloadCounts(deferred, failed);
    }

    private ReloadedCatalog restoreBaseCatalog(ToolRefreshRequest request, ToolSelection selection) {
        synchronized (toolCatalog) {
            var snapshot = toolCatalog.refresh(request);
            List<String> visibleNames =
                    toolCatalog.resolve(selection).stream().map(AgentTool::name).toList();
            return new ReloadedCatalog(snapshot.version(), snapshot.diagnostics(), visibleNames);
        }
    }

    private com.campusclaw.codingagent.settings.Settings.ToolsSettings currentToolsSettings() {
        if (settingsManager == null) {
            return null;
        }
        var settings = settingsManager.load();
        return settings != null ? settings.tools() : null;
    }

    private static Function<Settings.ToolsSettings, ToolSelection> fixedSelectionResolver(ToolSelection selection) {
        ToolSelection fixed = selection != null ? selection : ToolSelection.all();
        return ignored -> fixed;
    }

    /**
     * Attaches a {@link com.campusclaw.agent.subagent.SubAgentRegistry} so each session created
     * by this pool can cascade-cancel its sub-agents on abort.
     *
     * @param registry the registry
     */
    public void setSubAgentRegistry(com.campusclaw.agent.subagent.SubAgentRegistry registry) {
        this.subAgentRegistry = registry;
    }

    /**
     * Shuts down the cleaner thread and closes any open SessionManager writers.
     */
    public void shutdown() {
        cleaner.shutdownNow();
        sessions.values().forEach(e -> closeQuietly(e.session()));
        sessions.clear();
    }

    record SessionRef(String conversationId, AgentSession session) {}

    private AgentSession createSessionWithPersistence(String agentId, String conversationId) {
        PreparedAgentRuntime preparedRuntime = prepareRuntime(agentId);
        SessionConfig sessionConfig =
                preparedRuntime == null ? baseConfig : agentRuntimeManager.sessionConfig(baseConfig, preparedRuntime);
        Path configuredCwd =
                sessionConfig.cwd() != null ? sessionConfig.cwd() : Path.of(System.getProperty("user.dir"));
        Path absoluteCwd = configuredCwd.toAbsolutePath().normalize();
        sessionConfig = new SessionConfig(
                sessionConfig.model(), absoluteCwd, sessionConfig.customPrompt(), sessionConfig.mode());
        var skillLoader = new SkillLoader(sandboxParser, useSandbox);
        var skillExpander = new SkillExpander(sandboxParser, useSandbox);
        AgentSession session = new AgentSession(
                aiService, modelRegistry, promptBuilder, skillLoader, skillExpander, resolveTools(sessionConfig.cwd()));
        if (toolCatalog != null) {
            session.setToolCatalog(toolCatalog, toolSelection);
        }
        if (preparedRuntime != null) {
            session.setAgentRuntime(preparedRuntime, agentRuntimeManager);
            applyDelegationState(session, preparedRuntime, conversationId, skillLoader, skillExpander, sessionConfig);
        }
        if (subAgentRegistry != null) {
            session.setSubAgentRegistry(subAgentRegistry);
        }

        if (!persistenceEnabled) {
            session.initialize(sessionConfig);
            log.info("Created new conversation (in-memory only): {}", conversationId);
            return session;
        }

        SessionManager sm = new SessionManager();
        Path file = sessionFilePath(conversationId, sessionConfig.cwd());
        List<Message> restored = List.of();
        if (Files.exists(file)) {
            restored = sm.loadSession(file);
            log.info("Resumed conversation {} from disk ({} messages)", conversationId, restored.size());
        } else {
            sm.createSession(sessionConfig.cwd().toString(), conversationId);
            log.info("Created new conversation: {}", conversationId);
        }

        session.setSessionManager(sm);
        session.initialize(sessionConfig);

        if (!restored.isEmpty()) {
            session.getAgent().clearMessages();
            for (Message m : restored) {
                session.getAgent().getState().appendMessage(m);
            }
        }

        return session;
    }

    private void applyDelegationState(
            AgentSession session,
            PreparedAgentRuntime preparedRuntime,
            String conversationId,
            SkillLoader skillLoader,
            SkillExpander skillExpander,
            SessionConfig sessionConfig) {
        if (delegationDispatcher == null) {
            return;
        }
        session.setDelegationState(DelegationState.entry(
                delegationDispatcher,
                conversationId,
                null,
                new DelegationWiring(
                        aiService,
                        modelRegistry,
                        promptBuilder,
                        skillLoader,
                        skillExpander,
                        resolveTools(sessionConfig.cwd()),
                        toolCatalog,
                        toolSelection)));
    }

    private PreparedAgentRuntime prepareRuntime(String agentId) {
        if (agentId == null) {
            return null;
        }
        if (agentRuntimeManager == null) {
            throw new IllegalStateException("Managed Agent runtime is not configured");
        }
        return agentRuntimeManager.prepare(agentId);
    }

    /**
     * Returns the JSONL path that {@link SessionManager} would use for this id.
     *
     * @param sessionId the sessionId
     * @param cwd session working directory
     * @return the result
     */
    private Path sessionFilePath(String sessionId, Path cwd) {
        String cwdText = cwd.toAbsolutePath().normalize().toString();
        String safePath = "--" + cwdText.replaceFirst("^[/\\\\]", "").replaceAll("[/\\\\:]", "-") + "--";
        return AppPaths.SESSIONS_DIR.resolve(safePath).resolve(sessionId + ".jsonl");
    }

    private List<AgentTool> resolveTools(Path cwd) {
        if (toolCatalog == null) {
            return tools;
        }
        toolCatalog.refresh(new ToolRefreshRequest(cwd));
        return toolCatalog.resolve(toolSelection);
    }

    private String normalizeAgentId(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        return defaultAgentId != null && !defaultAgentId.isBlank() ? defaultAgentId : null;
    }

    private static String sessionKey(String agentId, String conversationId) {
        return agentId == null ? conversationId : agentId + '\u0000' + conversationId;
    }

    private static void validateConversationId(String conversationId) {
        if (conversationId == null
                || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("Invalid conversationId: " + conversationId);
        }
    }

    private void evictIdle() {
        long cutoff = now() - TimeUnit.MINUTES.toMillis(IDLE_TIMEOUT_MINUTES);
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().lastAccess() < cutoff
                    && !e.getValue().session().isStreaming()
                    && !e.getValue().session().isRuntimePromptActive()) {
                closeQuietly(e.getValue().session());
                log.info("Evicted idle conversation: {}", e.getKey());
                return true;
            }
            return false;
        });
    }

    private static void closeQuietly(AgentSession session) {
        SessionManager sm = session.getSessionManager();
        if (sm != null) {
            sm.close();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
