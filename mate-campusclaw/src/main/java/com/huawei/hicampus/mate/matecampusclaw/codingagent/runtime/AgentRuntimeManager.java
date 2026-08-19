/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.SessionConfig;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Resolves managed Agent runtimes from the local cache or CampusMate and materializes
 * the directory structure expected by the existing Skill loader.
 *
 * <p>Agent identifiers are validated against the same segment pattern declared in
 * {@code docs/openapi/campusclaw-api.yaml} before any path is resolved, so remote
 * {@code agent_id} values cannot traverse outside the agents root (ADR-0013 item 1
 * baseline). The remaining snapshot-hardening rules are still deferred (see
 * {@code docs/DEFERRED.md} and ADR-0013): symlink, tamper and drift validation,
 * response shape validation and atomic publication are not enforced in this
 * iteration. A local snapshot that fails to load simply falls through to
 * re-materialization.</p>
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class AgentRuntimeManager {

    /** Single path segment: no separators, leading alphanumerics, same as the OpenAPI {@code agent_id} pattern. */
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private static final String AGENT_METADATA_FILE = "agentId.json";
    private static final String SYSTEM_PROMPT_FILE = "systemPrompt.md";
    private static final String AGENT_SETTINGS_FILE = "setting.json";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SKILL_TOOLS_FILE = "tools.json";

    private final AgentRuntimeProperties properties;
    private final MateServiceClient mateServiceClient;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> prepareLocks = new ConcurrentHashMap<>();

    public AgentRuntimeManager(
            AgentRuntimeProperties properties, MateServiceClient mateServiceClient, ObjectMapper mapper) {
        this.properties = properties;
        this.mateServiceClient = mateServiceClient;
        this.mapper = mapper;
    }

    /**
     * Loads the local Agent runtime when its snapshot files exist, otherwise fetches
     * it from CampusMate and materializes the directory. An unloadable snapshot
     * falls through to re-materialization instead of failing closed. Preparation
     * is serialized per Agent ID only, so one Agent's cold start never blocks
     * another Agent's (potentially cached) preparation.
     *
     * @param agentId selected Agent identifier
     * @return immutable prepared runtime
     * @throws AgentRuntimeException when the runtime cannot be fetched or materialized
     * @throws IllegalArgumentException when {@code agentId} is blank or violates the segment pattern
     */
    public PreparedAgentRuntime prepare(String agentId) {
        Path agentRoot = requireValidAgentId(agentId);
        PreparedAgentRuntime cached = loadIfComplete(agentId, agentRoot);
        if (cached != null) {
            return cached;
        }
        ReentrantLock lock = prepareLocks.computeIfAbsent(agentId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return prepareRemotely(agentId, agentRoot);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Loads a locally cached Agent runtime without any remote call or directory
     * materialization. Used by read-only endpoints that must not trigger fetches.
     *
     * @param agentId selected Agent identifier
     * @return the cached runtime, or {@code null} when no complete local snapshot exists
     * @throws IllegalArgumentException when {@code agentId} is blank or violates the segment pattern
     */
    public PreparedAgentRuntime prepareCached(String agentId) {
        Path agentRoot = requireValidAgentId(agentId);
        return loadIfComplete(agentId, agentRoot);
    }

    private PreparedAgentRuntime prepareRemotely(String agentId, Path agentRoot) {
        PreparedAgentRuntime local = loadIfComplete(agentId, agentRoot);
        if (local != null) {
            return local;
        }
        AgentRuntime remote = mateServiceClient.getAgentRuntime(agentId);
        List<SkillInfo> skills = resolveSkills(remote.bindingSkills());
        materialize(agentRoot, remote, skills);

        PreparedAgentRuntime prepared = loadIfComplete(agentId, agentRoot);
        if (prepared == null) {
            throw new AgentRuntimeException("Agent runtime materialization failed: " + agentId);
        }
        return prepared;
    }

    private Path requireValidAgentId(String agentId) {
        if (agentId == null || !AGENT_ID_PATTERN.matcher(agentId).matches()) {
            throw new IllegalArgumentException("Invalid agentId: " + agentId);
        }
        Path agentsRoot = properties.agentsRoot().toAbsolutePath().normalize();
        return agentsRoot.resolve(agentId).normalize();
    }

    /**
     * Derives a managed session configuration from cached Agent metadata.
     *
     * @param base base CLI/server configuration
     * @param runtime prepared runtime
     * @return per-Agent session configuration
     */
    public SessionConfig sessionConfig(SessionConfig base, PreparedAgentRuntime runtime) {
        String model = runtime.metadata().defaultModel().orElse(base.model());
        String prompt = joinPrompts(readSystemPrompt(runtime), base.customPrompt());
        return new SessionConfig(model, runtime.agentRoot(), prompt, base.mode());
    }

    private String readSystemPrompt(PreparedAgentRuntime runtime) {
        Path systemPromptFile = runtime.agentRoot().resolve(".campusclaw").resolve(SYSTEM_PROMPT_FILE);
        if (!Files.isRegularFile(systemPromptFile)) {
            throw new AgentRuntimeException("Agent system prompt is missing: " + runtime.agentId());
        }
        try {
            return Files.readString(systemPromptFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentRuntimeException("Failed to load Agent system prompt: " + runtime.agentId(), e);
        }
    }

    /**
     * Loads all tools persisted with a bound Skill.
     *
     * @param runtime prepared Agent runtime
     * @param skillName bound Skill name
     * @return Skill tool names in snapshot order
     * @throws AgentRuntimeException when the Skill or its tools snapshot cannot be read
     */
    public List<String> loadSkillToolNames(PreparedAgentRuntime runtime, String skillName) {
        SkillInfo skill = runtime.findSkill(skillName)
                .orElseThrow(() -> new AgentRuntimeException(
                        "Skill is not bound to Agent " + runtime.agentId() + ": " + skillName));
        Path skillDir = runtime.agentRoot().resolve(".campusclaw/skills").resolve(skillName);
        try {
            return readSkillTools(skillDir, skill).stream().map(SkillTool::name).toList();
        } catch (IOException e) {
            throw new AgentRuntimeException("Failed to load Skill tools snapshot: " + skillName, e);
        }
    }

    private PreparedAgentRuntime loadIfComplete(String agentId, Path agentRoot) {
        Path campusClawDir = agentRoot.resolve(".campusclaw");
        Path metadataFile = campusClawDir.resolve(AGENT_METADATA_FILE);
        Path systemPromptFile = campusClawDir.resolve(SYSTEM_PROMPT_FILE);
        Path skillsDir = campusClawDir.resolve("skills");
        if (!Files.isDirectory(agentRoot)
                || !Files.isRegularFile(metadataFile)
                || !Files.isRegularFile(systemPromptFile)
                || !Files.isDirectory(skillsDir)) {
            return null;
        }
        AgentRuntime metadata;
        try {
            metadata = mapper.readValue(metadataFile.toFile(), AgentRuntime.class);
        } catch (IOException e) {
            return null;
        }
        List<SkillInfo> skills = loadBoundSkills(skillsDir, metadata.bindingSkills());
        return skills == null ? null : new PreparedAgentRuntime(agentId, agentRoot, metadata, skills);
    }

    /**
     * Rebuilds the bound-Skill list from the materialized SKILL.md front-matter headers. A
     * sub-directory with a parseable SKILL.md header is itself the proof that the Agent is
     * bound to that Skill; the declared reference count only guards against partially
     * materialized trees, which fall through to re-materialization.
     *
     * @param skillsDir directory containing one sub-directory per bound Skill
     * @param references binding references declared by the cached Agent metadata
     * @return the bound Skills sorted by name, or {@code null} when the snapshot is incomplete
     */
    private List<SkillInfo> loadBoundSkills(Path skillsDir, List<SkillReference> references) {
        List<SkillInfo> available = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : entries) {
                Path skillFile = entry.resolve(SKILL_FILE);
                if (Files.isRegularFile(skillFile)) {
                    SkillInfo skill = parseMaterializedSkill(Files.readString(skillFile, StandardCharsets.UTF_8));
                    if (skill != null) {
                        available.add(skill);
                    }
                }
            }
            long declared = references.stream().filter(Objects::nonNull).count();
            if (available.size() != declared) {
                return null;
            }
            available.sort(Comparator.comparing(SkillInfo::name));
            return List.copyOf(available);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reconstructs the minimal Skill identity snapshot from a SKILL.md front-matter header.
     *
     * @param content the SKILL.md content
     * @return Skill metadata carrying name and description only, or {@code null} when the
     *         header lacks a usable name or description
     */
    private static SkillInfo parseMaterializedSkill(String content) {
        Map<String, Object> frontmatter = SkillLoader.parseFrontmatter(content);
        String name = frontmatterValue(frontmatter, "name");
        String description = frontmatterValue(frontmatter, "description");
        if (name == null || description == null) {
            return null;
        }
        return new SkillInfo(name, null, null, description, null, List.of(), List.of(), List.of(), List.of());
    }

    private static String frontmatterValue(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private void materialize(Path agentRoot, AgentRuntime runtime, List<SkillInfo> skills) {
        try {
            writeRuntimeTree(agentRoot, runtime, skills);
        } catch (IOException e) {
            throw new AgentRuntimeException("Failed to materialize Agent runtime: " + runtime.id(), e);
        }
    }

    private void writeRuntimeTree(Path agentRoot, AgentRuntime runtime, List<SkillInfo> skills) throws IOException {
        Path campusClawDir = agentRoot.resolve(".campusclaw");
        Path skillsDir = campusClawDir.resolve("skills");

        // The skills directory is fully managed: re-materialization rewrites it from scratch
        // so stale Skill directories from an older binding cannot survive a re-fetch.
        deleteRecursively(skillsDir);
        Files.createDirectories(skillsDir);
        for (SkillInfo skill : skills) {
            Path skillDir = skillsDir.resolve(skillDirectoryName(skill));
            Path referencesDir = skillDir.resolve("references");
            Path templatesDir = skillDir.resolve("templates");
            Files.createDirectories(referencesDir);
            Files.createDirectories(templatesDir);
            writeResources(referencesDir, skill.references());
            writeFile(referencesDir.resolve(SKILL_TOOLS_FILE), renderSkillTools(skill));
            writeResources(templatesDir, skill.templates());
            writeFile(skillDir.resolve(SKILL_FILE), renderSkill(skill));
        }
        writeFile(campusClawDir.resolve(SYSTEM_PROMPT_FILE), systemPromptContent(runtime));
        writeFile(campusClawDir.resolve(AGENT_METADATA_FILE), mapper.writeValueAsString(runtime));
        writeFile(campusClawDir.resolve(AGENT_SETTINGS_FILE), renderModelSettings(runtime));
    }

    /**
     * Renders the model-selection settings file persisted next to the Agent metadata.
     * {@code agentVersion} keeps the numeric form when the version parses as a
     * whole number, otherwise the raw string is preserved.
     *
     * @param runtime Agent metadata
     * @return setting.json content
     */
    private String renderModelSettings(AgentRuntime runtime) {
        var node = mapper.createObjectNode();
        node.put("agentId", runtime.id() == null ? "" : runtime.id());
        String version = runtime.version();
        if (version != null && version.matches("-?\\d{1,18}")) {
            node.put("agentVersion", Long.parseLong(version));
        } else {
            node.put("agentVersion", version == null ? "" : version);
        }
        runtime.defaultModel().ifPresent(model -> node.put("defaultModel", model));
        var enabledModels = node.putArray("enabledModels");
        runtime.bindingModels().forEach(enabledModels::add);
        return node.toString();
    }

    private static String systemPromptContent(AgentRuntime runtime) {
        return runtime.systemPrompt() == null ? "" : runtime.systemPrompt();
    }

    private static String skillDirectoryName(SkillInfo skill) {
        return skill.name() == null || skill.name().isBlank() ? skill.id() : skill.name();
    }

    private void writeResources(Path directory, List<SkillFile> resources) throws IOException {
        for (SkillFile resource : resources) {
            if (resource == null) {
                continue;
            }
            writeFile(directory.resolve(resourceFileName(resource)), resource.content());
        }
    }

    private static String resourceFileName(SkillFile resource) {
        String type = resource.fileType() == null ? "" : resource.fileType().toLowerCase(Locale.ROOT);
        return resource.name() + "." + type;
    }

    /**
     * Renders the SKILL.md whose front-matter header is the single persisted source of the
     * Skill identity: {@code name} and {@code description}. No Skill id is persisted — the
     * materialized {@code skills/} sub-directories themselves prove which Skills the Agent
     * is bound to.
     *
     * @param skill Skill metadata fetched from CampusMate
     * @return the SKILL.md content
     * @throws IOException when the description cannot be JSON-escaped
     */
    private String renderSkill(SkillInfo skill) throws IOException {
        String description = firstNonBlank(skill.description(), skill.name());
        StringBuilder content = new StringBuilder();
        content.append("---\nname: ")
                .append(skill.name())
                .append("\ndescription: ")
                .append(mapper.writeValueAsString(description))
                .append("\n---\n\n");
        content.append("# ").append(skill.name()).append("\n\n");
        content.append(description).append('\n');
        if (hasText(skill.useCases())) {
            content.append("\n## Use cases\n\n").append(skill.useCases()).append('\n');
        }
        return content.toString();
    }

    private String renderSkillTools(SkillInfo skill) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(new SkillToolsFile(skillTools(skill)));
    }

    private List<SkillTool> readSkillTools(Path skillDir, SkillInfo skill) throws IOException {
        Path toolsFile = skillDir.resolve("references").resolve(SKILL_TOOLS_FILE);
        if (!Files.isRegularFile(toolsFile)) {
            throw new AgentRuntimeException("Skill tools snapshot is missing: " + skill.name());
        }
        return mapper.readValue(toolsFile.toFile(), SkillToolsFile.class).tools();
    }

    private static List<SkillTool> skillTools(SkillInfo skill) {
        List<SkillTool> tools = new ArrayList<>();
        for (BoundTool tool : skill.bindingTools()) {
            if (tool == null) {
                continue;
            }
            tools.add(new SkillTool(tool.id(), tool.name(), tool.description() == null ? "" : tool.description()));
        }
        return List.copyOf(tools);
    }

    private List<SkillInfo> resolveSkills(List<SkillReference> references) {
        List<SkillInfo> skills = new ArrayList<>();
        for (SkillReference reference : references) {
            if (reference == null) {
                continue;
            }
            List<SkillInfo> result = mateServiceClient.querySkillInfo(reference.id());
            if (result.isEmpty()) {
                throw new AgentRuntimeException("querySkillInfo returned no Skill for id " + reference.id());
            }
            skills.add(result.getFirst());
        }
        return List.copyOf(skills);
    }

    private static String joinPrompts(String runtimePrompt, String customPrompt) {
        if (runtimePrompt == null || runtimePrompt.isBlank()) {
            return customPrompt;
        }
        if (customPrompt == null || customPrompt.isBlank()) {
            return runtimePrompt;
        }
        return runtimePrompt + "\n\n" + customPrompt;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Managed Skill";
    }

    private static void writeFile(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SkillToolsFile(List<SkillTool> tools) {
        private SkillToolsFile {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    private record SkillTool(@JsonProperty("tool_id") String toolId, String name, String description) {}
}
