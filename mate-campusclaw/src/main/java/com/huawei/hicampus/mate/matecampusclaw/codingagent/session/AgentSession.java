/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEventListener;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.model.ModelRegistry;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.config.AppPaths;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.context.ContextFileLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.context.ContextFileLoader.ContextFile;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.PromptTemplate;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.PromptTemplateEntry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.PromptTemplateLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptBuilder;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt.SystemPromptConfig;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.ActivateSkillTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.Skill;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillExpander;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillStateStore;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolCatalog;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolRefreshRequest;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolSelection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a single agent session lifecycle: initialization, prompt handling,
 * skill expansion, and history access.
 *
 * <p>Coordinates {@link Agent}, {@link ModelRegistry}, {@link SkillLoader},
 * {@link SystemPromptBuilder}, and {@link SkillExpander} to provide a cohesive
 * session abstraction for the coding agent CLI.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    static final Path USER_AGENT_DIR = AppPaths.USER_AGENT_DIR;
    static final Path USER_SKILLS_DIR = AppPaths.USER_SKILLS_DIR;
    static final String PROJECT_SKILLS_SUBDIR = AppPaths.PROJECT_SKILLS_SUBDIR;

    private final CampusClawAiService piAiService;
    private final ModelRegistry modelRegistry;
    private final SystemPromptBuilder promptBuilder;
    private final SkillLoader skillLoader;
    private final SkillExpander skillExpander;
    private List<AgentTool> tools;
    private final ContextFileLoader contextFileLoader;
    private final PromptTemplateLoader promptTemplateLoader;
    private SessionManager sessionManager;
    private ToolCatalog toolCatalog;
    private volatile ToolSelection toolSelection = ToolSelection.all();
    private Path initializedCwd;
    private List<AgentTool> locallyVisibleTools = List.of();
    private List<AgentTool> baseTools = List.of();
    private Map<String, List<String>> managedSkillToolNames = Map.of();
    private PreparedAgentRuntime preparedRuntime;
    private AgentRuntimeManager agentRuntimeManager;
    private String initializedCustomPrompt;
    private final Object runtimeLifecycleLock = new Object();
    private boolean runtimePromptActive;
    private boolean runtimeReloadInProgress;
    private boolean runtimeReloadPending;

    private final SkillRegistry skillRegistry = new SkillRegistry();
    private List<PromptTemplateEntry> promptTemplates = List.of();
    private Agent agent;
    private boolean initialized;
    private com.huawei.hicampus.mate.matecampusclaw.agent.subagent.SubAgentRegistry subAgentRegistry;

    public AgentSession(
            CampusClawAiService piAiService,
            ModelRegistry modelRegistry,
            SystemPromptBuilder promptBuilder,
            SkillLoader skillLoader,
            SkillExpander skillExpander,
            List<AgentTool> tools) {
        this.piAiService = Objects.requireNonNull(piAiService, "piAiService");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader");
        this.skillExpander = Objects.requireNonNull(skillExpander, "skillExpander");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        this.contextFileLoader = new ContextFileLoader();
        this.promptTemplateLoader = new PromptTemplateLoader();
    }

    /**
     * Enables dynamic tool resolution for this session.
     *
     * @param toolCatalog the catalog to refresh and resolve
     * @param toolSelection the visibility selection for this session
     */
    public void setToolCatalog(ToolCatalog toolCatalog, ToolSelection toolSelection) {
        this.toolCatalog = toolCatalog;
        setToolSelection(toolSelection);
    }

    /**
     * Updates the visibility filter used by the next catalog refresh.
     *
     * @param toolSelection current effective selection
     */
    public void setToolSelection(ToolSelection toolSelection) {
        this.toolSelection = toolSelection != null ? toolSelection : ToolSelection.all();
    }

    /**
     * Enables the managed Agent/Skill runtime for this session.
     *
     * @param runtime prepared local Agent runtime
     * @param runtimeManager runtime API/cache manager
     * @throws IllegalStateException when called after initialization
     */
    public void setAgentRuntime(PreparedAgentRuntime runtime, AgentRuntimeManager runtimeManager) {
        if (initialized) {
            throw new IllegalStateException("Agent runtime must be set before session initialization");
        }
        this.preparedRuntime = Objects.requireNonNull(runtime, "runtime");
        this.agentRuntimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
    }

    /**
     * Initializes the session: resolves the model, loads skills, builds the
     * system prompt, registers tools, and configures the agent.
     *
     * @param config the session configuration
     * @throws IllegalStateException    if the session is already initialized
     * @throws IllegalArgumentException if the model cannot be resolved
     */
    public void initialize(SessionConfig config) {
        Objects.requireNonNull(config, "config");
        if (initialized) {
            throw new IllegalStateException("Session is already initialized");
        }

        // 1. Resolve model
        String modelId = config.model() != null ? config.model() : DEFAULT_MODEL;
        Model model = resolveModel(modelId);

        // 2. Load skills (user-level + project-level)
        Path cwd = config.cwd() != null ? config.cwd() : Path.of(System.getProperty("user.dir"));
        initializedCwd = cwd;
        initializedCustomPrompt = config.customPrompt();
        refreshTools(cwd);
        locallyVisibleTools = List.copyOf(tools);
        loadSkills(cwd);
        configureRuntimeTools();

        // 3. Load context files (AGENTS.md / CLAUDE.md)
        List<ContextFile> contextFiles = contextFileLoader.loadProjectContextFiles(cwd, USER_AGENT_DIR);

        // 4. Discover SYSTEM.md and APPEND_SYSTEM.md
        String systemPromptOverride = contextFileLoader.loadSystemPrompt(cwd, USER_AGENT_DIR);
        String appendSystemPrompt = contextFileLoader.loadAppendSystemPrompt(cwd, USER_AGENT_DIR);

        // 5. Load prompt templates
        promptTemplates = promptTemplateLoader.load(cwd, USER_AGENT_DIR);

        // 6. Build system prompt
        List<Skill> visibleSkills = skillRegistry.getVisibleSkills();
        Map<String, String> env = buildEnvironmentMap();

        SystemPromptConfig promptConfig = new SystemPromptConfig(
                tools,
                visibleSkills,
                cwd,
                config.customPrompt(),
                env,
                contextFiles,
                systemPromptOverride,
                appendSystemPrompt,
                preparedRuntime != null);
        String systemPrompt = promptBuilder.build(promptConfig);

        // 7. Create and configure Agent
        agent = createAgent(piAiService);
        agent.setModel(model);
        agent.setSystemPrompt(systemPrompt);
        agent.setTools(baseTools);

        initialized = true;
    }

    /**
     * Sends a user prompt to the agent. Expands {@code /skill:name} commands
     * before forwarding to the underlying agent.
     *
     * @param userInput the raw user input
     * @return a future that completes when the agent finishes processing
     * @throws IllegalStateException if the session is not initialized
     */
    public CompletableFuture<Void> prompt(String userInput) {
        requireInitialized();
        Objects.requireNonNull(userInput, "userInput");

        // Expand prompt templates first (/templatename args...)
        String expanded = expandPromptTemplate(userInput);

        if (!beginRuntimePrompt()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Agent is already running"));
        }
        try {
            if (preparedRuntime == null) {
                expanded = skillExpander.expand(expanded, skillRegistry);
            } else {
                agent.setTools(baseTools);
                var explicitSkill = SkillExpander.extractSkillName(expanded);
                if (explicitSkill.isPresent()) {
                    activateRuntimeSkill(explicitSkill.get());
                }
                expanded = skillExpander.expand(expanded, skillRegistry);
            }
            CompletableFuture<Void> promptFuture = agent.prompt(expanded);
            return promptFuture.whenComplete((ignored, failure) -> resetRuntimePromptState());
        } catch (RuntimeException e) {
            resetRuntimePromptState();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Aborts the current agent execution.
     *
     * @throws IllegalStateException if the session is not initialized
     */
    public void abort() {
        requireInitialized();
        agent.abort();
        var registry = subAgentRegistry;
        if (registry != null) {
            registry.cancelAll("parent-abort");
        }
    }

    /**
     * Attaches a {@link com.huawei.hicampus.mate.matecampusclaw.agent.subagent.SubAgentRegistry} so {@link #abort()} can
     * cascade-cancel any open sub-agent sessions. Without this, sub-agent cancellation still
     * happens through the per-tool {@code CancellationToken}, but stale sessions across edge cases
     * (e.g. between turns) would be missed.
     *
     * @param registry the registry
     */
    public void setSubAgentRegistry(com.huawei.hicampus.mate.matecampusclaw.agent.subagent.SubAgentRegistry registry) {
        this.subAgentRegistry = registry;
    }

    /**
     * Returns the conversation history.
     *
     * @return an unmodifiable list of messages
     * @throws IllegalStateException if the session is not initialized
     */
    public List<Message> getHistory() {
        requireInitialized();
        return agent.getState().getMessages();
    }

    /**
     * Returns the underlying agent instance.
     *
     * @return the configured agent
     * @throws IllegalStateException if the session is not initialized
     */
    public Agent getAgent() {
        requireInitialized();
        return agent;
    }

    /**
     * Returns the skill registry for this session.
     *
     * @return the result
     */
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /**
     * Returns whether this session has been initialized.
     *
     * @return the result
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Subscribes to agent events. Delegates to the underlying {@link Agent}.
     *
     * @param listener the event listener
     * @return a runnable that removes the listener when called
     * @throws IllegalStateException if the session is not initialized
     */
    public Runnable subscribe(AgentEventListener listener) {
        requireInitialized();
        return agent.subscribe(listener);
    }

    /**
     * Steers the running agent with an additional user message.
     *
     * @param message the steering message text
     * @throws IllegalStateException if the session is not initialized
     */
    public void steer(String message) {
        requireInitialized();
        Objects.requireNonNull(message, "message");
        agent.steer(new UserMessage(message, System.currentTimeMillis()));
    }

    /**
     * Returns the current model ID, or {@code "unknown"} if no model is set.
     *
     * @return the current model identifier, or {@code "unknown"} when unset
     * @throws IllegalStateException if the session is not initialized
     */
    public String getModelId() {
        requireInitialized();
        var model = agent.getState().getModel();
        return model != null ? model.id() : "unknown";
    }

    /**
     * Returns the working directory used to initialize this session.
     *
     * @return initialized working directory
     */
    public Path getInitializedCwd() {
        requireInitialized();
        return initializedCwd;
    }

    /**
     * Returns whether the agent is currently streaming a response.
     *
     * @return {@code true} if a streaming response is in progress
     * @throws IllegalStateException if the session is not initialized
     */
    public boolean isStreaming() {
        requireInitialized();
        return agent.getState().isStreaming();
    }

    /**
     * Returns whether this session uses a CampusMate-managed Agent runtime.
     *
     * @return {@code true} for an Agent-scoped managed session
     */
    public boolean isManagedRuntime() {
        return preparedRuntime != null;
    }

    /**
     * Returns whether a turn owns this session's mutable prompt/tool state.
     *
     * @return {@code true} while a prompt is running
     */
    public boolean isRuntimePromptActive() {
        synchronized (runtimeLifecycleLock) {
            return runtimePromptActive;
        }
    }

    /**
     * Changes the model used by the agent. Resolves the model ID via the
     * registry and updates the underlying agent.
     *
     * @param modelId the model identifier to switch to
     * @throws IllegalStateException    if the session is not initialized
     * @throws IllegalArgumentException if the model cannot be resolved
     */
    public void setModel(String modelId) {
        requireInitialized();
        Objects.requireNonNull(modelId, "modelId");
        Model model = resolveModel(modelId);
        agent.setModel(model);
    }

    /**
     * Resets the agent state to start a fresh conversation while keeping the
     * same session configuration (model, tools, system prompt).
     *
     * @throws IllegalStateException if the session is not initialized
     */
    public void newSession() {
        requireInitialized();
        agent.clearMessages();
    }

    /**
     * Returns the loaded prompt templates for this session.
     *
     * @return the result
     */
    public List<PromptTemplateEntry> getPromptTemplates() {
        return promptTemplates;
    }

    /**
     * Sets the session manager for persistence. Must be called before initialize().
     *
     * @param sessionManager the sessionManager
     */
    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Returns the session manager, or null if not set.
     *
     * @return the result
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Reloads skills, prompt templates, context files, and rebuilds the system prompt.
     * Called by the /reload command.
     *
     * @param customPrompt the user-supplied custom prompt (may be null)
     */
    public void reload(String customPrompt) {
        requireInitialized();
        String effectivePrompt = customPrompt != null ? customPrompt : initializedCustomPrompt;
        claimRuntimeReloadOrThrow();
        runClaimedRuntimeReload(effectivePrompt, true, true);
    }

    /**
     * Reloads session-facing state after the shared catalog has already been refreshed.
     */
    public void reloadFromCatalogSnapshot() {
        requireInitialized();
        claimRuntimeReloadOrThrow();
        runClaimedRuntimeReload(initializedCustomPrompt, false, true);
    }

    /**
     * Refreshes this session with its own catalog cwd, or queues the refresh at the
     * current managed turn boundary.
     *
     * @return {@code true} when reloaded immediately; {@code false} when queued
     */
    public boolean reloadToolsWhenIdle() {
        requireInitialized();
        synchronized (runtimeLifecycleLock) {
            if (runtimePromptActive || runtimeReloadInProgress) {
                runtimeReloadPending = true;
                return false;
            }
            runtimeReloadInProgress = true;
        }
        runClaimedRuntimeReload(initializedCustomPrompt, true, true);
        return true;
    }

    private void reloadInternal(String customPrompt, boolean refreshCatalog) {
        requireInitialized();
        Path cwd = initializedCwd != null ? initializedCwd : Path.of(System.getProperty("user.dir"));
        if (toolCatalog != null) {
            if (refreshCatalog) {
                refreshTools(cwd);
            } else {
                resolveToolsFromCatalogSnapshot();
            }
            locallyVisibleTools = List.copyOf(tools);
        }

        // Reload skills
        loadSkills(cwd);
        configureRuntimeTools();

        // Reload prompt templates
        promptTemplates = promptTemplateLoader.load(cwd, USER_AGENT_DIR);

        // Reload context files and rebuild system prompt
        List<ContextFile> contextFiles = contextFileLoader.loadProjectContextFiles(cwd, USER_AGENT_DIR);
        String systemPromptOverride = contextFileLoader.loadSystemPrompt(cwd, USER_AGENT_DIR);
        String appendSystemPrompt = contextFileLoader.loadAppendSystemPrompt(cwd, USER_AGENT_DIR);

        List<Skill> visibleSkills = skillRegistry.getVisibleSkills();
        Map<String, String> env = buildEnvironmentMap();

        SystemPromptConfig promptConfig = new SystemPromptConfig(
                tools,
                visibleSkills,
                cwd,
                customPrompt,
                env,
                contextFiles,
                systemPromptOverride,
                appendSystemPrompt,
                preparedRuntime != null);
        String systemPrompt = promptBuilder.build(promptConfig);
        agent.setSystemPrompt(systemPrompt);
        agent.setTools(baseTools);
        initializedCustomPrompt = customPrompt;
    }

    /**
     * Overload for backward compatibility.
     */
    public void reload() {
        reload(null);
    }

    /**
     * Expands a prompt template command like "/templatename arg1 arg2".
     *
     * @param input the input
     * @return the result
     */
    String expandPromptTemplate(String input) {
        if (!input.startsWith("/")) {
            return input;
        }

        int spaceIdx = input.indexOf(' ');
        String name = spaceIdx >= 0 ? input.substring(1, spaceIdx) : input.substring(1);
        String argsStr = spaceIdx >= 0 ? input.substring(spaceIdx + 1) : "";

        for (PromptTemplateEntry template : promptTemplates) {
            if (template.name().equals(name)) {
                List<String> args = parseCommandArgs(argsStr);
                return PromptTemplate.expand(template.content(), args);
            }
        }
        return input;
    }

    static List<String> parseCommandArgs(String argsString) {
        List<String> args = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character inQuote = null;

        for (int i = 0; i < argsString.length(); i++) {
            char ch = argsString.charAt(i);
            if (inQuote != null) {
                if (ch == inQuote) {
                    inQuote = null;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                inQuote = ch;
            } else if (ch == ' ' || ch == '\t') {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    // -- package-private for testing --

    Agent createAgent(CampusClawAiService aiService) {
        return new Agent(aiService);
    }

    private void refreshTools(Path cwd) {
        if (toolCatalog == null) {
            return;
        }
        tools = List.copyOf(toolCatalog.resolve(new ToolRefreshRequest(cwd), toolSelection));
    }

    private void resolveToolsFromCatalogSnapshot() {
        if (toolCatalog == null) {
            return;
        }
        synchronized (toolCatalog) {
            tools = List.copyOf(toolCatalog.resolve(toolSelection));
        }
    }

    /**
     * Resolves a model from a pattern string.
     * Supports:
     * - Exact ID: "glm-5"
     * - Provider/ID: "zai/glm-5"
     * - Fuzzy substring: "sonnet" matches "claude-sonnet-4-20250514"
     * - Thinking suffix: "sonnet:high" (thinking level is stripped, not applied here)
     *
     * @param modelId the model pattern (id, provider/id, or fuzzy substring; may include a thinking suffix)
     * @return the resolved {@link Model}
     * @throws IllegalArgumentException when no model matches the given pattern
     */
    Model resolveModel(String modelId) {
        // Strip thinking level suffix (e.g., "sonnet:high" → "sonnet")
        int colonIdx = modelId.indexOf(':');
        String pattern = colonIdx >= 0 ? modelId.substring(0, colonIdx) : modelId;

        // Check for provider/id prefix
        Provider targetProvider = null;
        if (pattern.contains("/")) {
            String[] parts = pattern.split("/", 2);
            String providerStr = parts[0].toLowerCase(Locale.ROOT);
            pattern = parts[1];
            for (Provider p : modelRegistry.getProviders()) {
                if (p.value().toLowerCase(Locale.ROOT).contains(providerStr)) {
                    targetProvider = p;
                    break;
                }
            }
        }

        // 1. Exact match
        for (Provider provider : modelRegistry.getProviders()) {
            if (targetProvider != null && provider != targetProvider) {
                continue;
            }
            var model = modelRegistry.getModel(provider, pattern);
            if (model.isPresent()) {
                return model.get();
            }
        }

        // 2. Fuzzy substring match on id and name
        String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        Model bestMatch = null;
        for (Provider provider : modelRegistry.getProviders()) {
            if (targetProvider != null && provider != targetProvider) {
                continue;
            }
            for (Model m : modelRegistry.getModels(provider)) {
                if (m.id().toLowerCase(Locale.ROOT).contains(lowerPattern)
                        || m.name().toLowerCase(Locale.ROOT).contains(lowerPattern)) {
                    if (bestMatch == null || m.id().length() < bestMatch.id().length()) {
                        bestMatch = m; // Prefer shorter ID (more specific match)
                    }
                }
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }

        throw new IllegalArgumentException(
                "Unknown model: " + modelId + ". Use --list-models to see available models.");
    }

    void loadSkills(Path cwd) {
        skillRegistry.clear();
        managedSkillToolNames = Map.of();

        if (preparedRuntime == null) {
            // User-level skills: ~/.campusclaw/agent/skills/
            // Filter out skills explicitly disabled via the REST API.
            java.util.Set<String> disabled = new SkillStateStore(userSkillsDir()).loadDisabled();
            List<Skill> userSkills = skillLoader.loadFromDirectory(userSkillsDir(), "user").stream()
                    .filter(s -> !disabled.contains(s.name()))
                    .toList();
            skillRegistry.registerAll(userSkills);
        }

        // Project-level skills: {cwd}/.campusclaw/skills/
        Path projectSkillsDir = cwd.resolve(PROJECT_SKILLS_SUBDIR);
        if (preparedRuntime != null) {
            List<Skill> managedSkills = new java.util.ArrayList<>();
            Map<String, List<String>> toolNames = new LinkedHashMap<>();
            for (var metadata : preparedRuntime.skills()) {
                managedSkills.add(skillLoader.loadFromFile(
                        projectSkillsDir.resolve(metadata.name()).resolve("SKILL.md"), "project"));
                toolNames.put(
                        metadata.name(), agentRuntimeManager.loadSkillToolNames(preparedRuntime, metadata.name()));
            }
            managedSkillToolNames = Map.copyOf(toolNames);
            skillRegistry.registerAll(managedSkills);
            return;
        }
        List<Skill> projectSkills = skillLoader.loadFromDirectory(projectSkillsDir, "project");
        skillRegistry.registerAll(projectSkills);
    }

    private void configureRuntimeTools() {
        if (preparedRuntime == null) {
            baseTools = List.copyOf(tools);
            return;
        }
        Set<String> allowedAgentTools = Set.copyOf(preparedRuntime.allowedAgentToolNames());
        var configured = new LinkedHashMap<String, AgentTool>();
        locallyVisibleTools.stream()
                .filter(tool -> allowedAgentTools.contains(tool.name()))
                .forEach(tool -> configured.put(tool.name(), tool));
        AgentTool activationTool = new ActivateSkillTool(this::activateSkillFromTool);
        configured.put(activationTool.name(), activationTool);
        tools = List.copyOf(configured.values());
        baseTools = tools;
    }

    private AgentToolResult activateSkillFromTool(String skillName) {
        String instructions = activateRuntimeSkill(skillName);
        return new AgentToolResult(List.of(new TextContent(instructions)), null);
    }

    private String activateRuntimeSkill(String skillName) {
        Skill skill = skillRegistry
                .getByName(skillName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Skill: " + skillName));
        List<String> requestedTools = managedSkillToolNames.get(skillName);
        if (requestedTools == null) {
            throw new IllegalStateException("Skill tools were not loaded: " + skillName);
        }
        Map<String, AgentTool> localTools = new LinkedHashMap<>();
        locallyVisibleTools.forEach(tool -> localTools.put(tool.name(), tool));
        List<String> missingTools = requestedTools.stream()
                .filter(toolName -> !localTools.containsKey(toolName))
                .toList();
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException(
                    "Skill tools are not installed locally: " + String.join(", ", missingTools));
        }

        String instructions = skillExpander.expand(skill, null);
        var activated = new LinkedHashMap<String, AgentTool>();
        baseTools.forEach(tool -> activated.put(tool.name(), tool));
        requestedTools.forEach(toolName -> activated.put(toolName, localTools.get(toolName)));
        agent.setTools(List.copyOf(activated.values()));
        return instructions;
    }

    private void resetRuntimePromptState() {
        boolean reloadPending;
        synchronized (runtimeLifecycleLock) {
            agent.setTools(baseTools);
            runtimePromptActive = false;
            reloadPending = runtimeReloadPending;
            if (reloadPending) {
                runtimeReloadPending = false;
                runtimeReloadInProgress = true;
            }
        }
        if (reloadPending) {
            runClaimedRuntimeReload(initializedCustomPrompt, true, false);
        }
    }

    private boolean beginRuntimePrompt() {
        synchronized (runtimeLifecycleLock) {
            if (runtimePromptActive || runtimeReloadInProgress) {
                return false;
            }
            runtimePromptActive = true;
            return true;
        }
    }

    private void claimRuntimeReloadOrThrow() {
        synchronized (runtimeLifecycleLock) {
            if (runtimePromptActive || runtimeReloadInProgress) {
                throw new IllegalStateException("Cannot reload a managed Agent while it is busy");
            }
            runtimeReloadInProgress = true;
        }
    }

    private void runClaimedRuntimeReload(String customPrompt, boolean refreshCatalog, boolean propagateFailure) {
        RuntimeException firstFailure = null;
        boolean repeat;
        do {
            try {
                reloadInternal(customPrompt, refreshCatalog);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.warn("Agent session tool reload failed", e);
            }
            synchronized (runtimeLifecycleLock) {
                repeat = runtimeReloadPending && !runtimePromptActive;
                runtimeReloadPending = false;
                if (!repeat) {
                    runtimeReloadInProgress = false;
                }
            }
            customPrompt = initializedCustomPrompt;
            refreshCatalog = true;
        } while (repeat);
        if (propagateFailure && firstFailure != null) {
            throw firstFailure;
        }
    }

    protected Path userSkillsDir() {
        return USER_SKILLS_DIR;
    }

    static Map<String, String> buildEnvironmentMap() {
        Map<String, String> env = new HashMap<>();
        env.put("OS_NAME", System.getProperty("os.name", "unknown"));
        env.put("JAVA_VERSION", System.getProperty("java.version", "unknown"));
        return env;
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Session is not initialized. Call initialize() first.");
        }
    }
}
