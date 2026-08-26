/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.DependentSkill;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.Skill;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoadException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.SkillLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 以缓存优先和原子发布方式准备、刷新受管 Agent 运行目录。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class AgentRuntimeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeManager.class);

    private static final int SCHEMA_VERSION = 1;
    private static final String CAMPUSCLAW_DIRECTORY = ".campusclaw";
    private static final String AGENT_FILE = "agent.json";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String SYSTEM_FILE = "SYSTEM.md";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SKILL_MANIFEST_FILE = "skill.json";

    private final AgentRuntimeProperties properties;
    private final MateServiceClient mateServiceClient;

    // 复用真实会话的同一套 SKILL.md 加载校验,发布前确认落盘文件可被会话加载。
    private final SkillLoader skillLoader = new SkillLoader();
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AgentRuntimeManager(
            AgentRuntimeProperties properties, MateServiceClient mateServiceClient, ObjectMapper mapper) {
        this.properties = properties;
        this.mateServiceClient = mateServiceClient;
        this.mapper = mapper;
    }

    /**
     * 完整缓存存在时直接使用，否则访问 Mate 并原子创建运行目录。
     *
     * @param agentId Agent 标识
     * @return 已准备运行时
     */
    public PreparedAgentRuntime prepare(String agentId) {
        Path agentRoot = requireAgentRoot(agentId);
        PreparedAgentRuntime cached = loadIfComplete(agentId, agentRoot);
        if (cached != null) {
            return cached;
        }
        return withAgentLock(agentId, () -> {
            PreparedAgentRuntime rechecked = loadIfComplete(agentId, agentRoot);
            return rechecked != null ? rechecked : rebuild(agentId, agentRoot);
        });
    }

    /**
     * 仅加载当前完整缓存，不访问 Mate。
     *
     * @param agentId Agent 标识
     * @return 完整缓存；不存在时为空
     */
    public PreparedAgentRuntime prepareCached(String agentId) {
        Path agentRoot = requireAgentRoot(agentId);
        return loadIfComplete(agentId, agentRoot);
    }

    /**
     * 强制从 Mate 重建受管目录；失败时保留旧目录。
     *
     * @param agentId Agent 标识
     * @return 刷新后的运行时
     */
    public PreparedAgentRuntime refresh(String agentId) {
        Path agentRoot = requireAgentRoot(agentId);
        return withAgentLock(agentId, () -> rebuild(agentId, agentRoot));
    }

    /**
     * 读取已校验运行目录中的系统提示词。
     *
     * @param runtime 已准备运行时
     * @return 系统提示词
     * @throws AgentRuntimeException 系统提示词无法读取时抛出
     */
    public String readSystemPrompt(PreparedAgentRuntime runtime) {
        Path systemFile = runtime.agentRoot().resolve(CAMPUSCLAW_DIRECTORY).resolve(SYSTEM_FILE);
        try {
            return Files.readString(systemFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AgentRuntimeException("Agent system prompt is unavailable", exception);
        }
    }

    private PreparedAgentRuntime rebuild(String agentId, Path agentRoot) {
        AgentRuntime remote = mateServiceClient.getAgentRuntime(agentId);
        requireValidRuntime(remote, agentId);
        List<SkillInfo> skills = resolveSkills(remote.bindingSkills());
        Path staging = createStagingDirectory(agentRoot);
        try {
            writeRuntimeTree(staging, remote, skills);
            PreparedAgentRuntime validated = loadSnapshot(agentId, agentRoot, staging);
            if (validated == null) {
                throw new AgentRuntimeException("Generated Agent runtime is incomplete");
            }
            publish(agentId, agentRoot, staging);
            PreparedAgentRuntime published = loadIfComplete(agentId, agentRoot);
            if (published == null) {
                throw new AgentRuntimeException("Published Agent runtime is incomplete");
            }
            return published;
        } catch (IOException exception) {
            throw new AgentRuntimeException("Failed to rebuild Agent runtime", exception);
        } finally {
            deleteQuietly(staging);
        }
    }

    private Path createStagingDirectory(Path agentRoot) {
        try {
            Files.createDirectories(properties.agentsRoot().toAbsolutePath().normalize());
            Files.createDirectories(agentRoot);
            if (Files.isSymbolicLink(agentRoot)) {
                throw new IOException("Agent root must not be a symbolic link");
            }
            return Files.createTempDirectory(agentRoot, ".campusclaw-stage-");
        } catch (IOException exception) {
            throw new AgentRuntimeException("Failed to create Agent staging directory", exception);
        }
    }

    private void writeRuntimeTree(Path campusClawDir, AgentRuntime runtime, List<SkillInfo> skills) throws IOException {
        Files.createDirectories(campusClawDir.resolve("agents"));
        Files.createDirectories(campusClawDir.resolve("skills"));
        writeJson(campusClawDir.resolve(AGENT_FILE), toIdentity(runtime));
        writeJson(campusClawDir.resolve(SETTINGS_FILE), toSettings(runtime));
        writeFile(campusClawDir.resolve(SYSTEM_FILE), runtime.systemPrompt());
        writeChildren(campusClawDir.resolve("agents"), runtime.bindingAgents());
        writeSkills(campusClawDir.resolve("skills"), skills);
    }

    private void writeChildren(Path agentsDirectory, List<AgentReference> children) throws IOException {
        Set<String> names = new HashSet<>();
        for (AgentReference child : children) {
            requireSafeUniqueName(child.name(), names, "Child Agent");
            ChildManifest manifest = new ChildManifest(
                    SCHEMA_VERSION,
                    child.id(),
                    child.name(),
                    child.displayName(),
                    child.description(),
                    child.version(),
                    child.enabled());
            writeJson(agentsDirectory.resolve(child.name() + ".json"), manifest);
        }
    }

    private void writeSkills(Path skillsDirectory, List<SkillInfo> skills) throws IOException {
        Set<String> names = new HashSet<>();
        for (SkillInfo skill : skills) {
            requireSafeUniqueName(skill.name(), names, "Skill");
            requireLoadableSkillMarkdown(skill);
            Path skillDirectory = skillsDirectory.resolve(skill.name());
            Files.createDirectories(skillDirectory.resolve("references"));
            Files.createDirectories(skillDirectory.resolve("templates"));
            writeJson(
                    skillDirectory.resolve(SKILL_MANIFEST_FILE),
                    new SkillManifest(SCHEMA_VERSION, skill.id(), skill.name(), skill.version()));
            Path skillFile = skillDirectory.resolve(SKILL_FILE);
            writeFile(skillFile, skill.content());
            requireSessionLoadable(skill.name(), skillFile);
            writeResources(skillDirectory.resolve("references"), skill.references());
            writeResources(skillDirectory.resolve("templates"), skill.templates());
        }
    }

    // 发布前校验 SKILL.md 正文:非空、不超大小上限、frontmatter name 与
    // querySkillInfo 响应的 name 一致、description 必填。
    private static void requireLoadableSkillMarkdown(SkillInfo skill) {
        String content = skill.content();
        if (isBlank(content)) {
            throw new AgentRuntimeException("Skill content is empty: " + skill.name());
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > Skill.MAX_FILE_BYTES) {
            throw new AgentRuntimeException("Skill content exceeds size limit: " + skill.name());
        }
        Map<String, Object> frontmatter = SkillLoader.parseFrontmatter(content);
        String frontmatterName = frontmatterValue(frontmatter, "name");
        if (frontmatterName == null || !frontmatterName.equals(skill.name())) {
            throw new AgentRuntimeException("SKILL.md frontmatter name does not match Skill metadata: " + skill.name());
        }
        if (isBlank(frontmatterValue(frontmatter, "description"))) {
            throw new AgentRuntimeException("SKILL.md frontmatter description is required: " + skill.name());
        }
    }

    // 统一的 SKILL.md 会话可加载校验入口:字节上限与 SkillLoader 完整规则
    // (名称正则、name/description 长度限制)。发布前复核与缓存读取共用,
    // 解析出的名称还必须与期望名称一致。
    private Skill requireSessionLoadable(String expectedName, Path skillFile) {
        try {
            if (Files.size(skillFile) > Skill.MAX_FILE_BYTES) {
                throw new AgentRuntimeException("SKILL.md exceeds size limit: " + expectedName);
            }
        } catch (IOException exception) {
            throw new AgentRuntimeException("SKILL.md is unreadable: " + expectedName, exception);
        }
        Skill loaded;
        try {
            loaded = skillLoader.loadFromFile(skillFile, "managed");
        } catch (SkillLoadException exception) {
            throw new AgentRuntimeException("SKILL.md is not session-loadable: " + expectedName, exception);
        }
        if (!loaded.name().equals(expectedName)) {
            throw new AgentRuntimeException("SKILL.md parsed name does not match Skill metadata: " + expectedName);
        }
        return loaded;
    }

    // frontmatter 取值:键缺失或值为 null 时返回 null。
    // String.valueOf 会把缺失键渲染成 "null" 字符串,不能直接使用。
    private static String frontmatterValue(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void writeResources(Path directory, List<SkillFile> resources) throws IOException {
        Set<String> names = new HashSet<>();
        for (SkillFile resource : resources) {
            if (resource == null) {
                continue;
            }
            String fileName = resourceFileName(resource);
            requireSafeUniqueName(fileName, names, "Skill resource");
            writeFile(directory.resolve(fileName), resource.content());
        }
    }

    private PreparedAgentRuntime loadIfComplete(String agentId, Path agentRoot) {
        return loadSnapshot(agentId, agentRoot, agentRoot.resolve(CAMPUSCLAW_DIRECTORY));
    }

    private PreparedAgentRuntime loadSnapshot(String agentId, Path agentRoot, Path campusClawDir) {
        if (!isSafeDirectory(campusClawDir) || containsForbiddenEntry(campusClawDir)) {
            return null;
        }
        try {
            AgentIdentity identity = readJson(campusClawDir.resolve(AGENT_FILE), AgentIdentity.class);
            AgentSettings settings = readJson(campusClawDir.resolve(SETTINGS_FILE), AgentSettings.class);
            String systemPrompt = readRequiredFile(campusClawDir.resolve(SYSTEM_FILE));
            List<AgentReference> children = loadChildren(campusClawDir.resolve("agents"));
            List<SkillInfo> skills = loadSkills(campusClawDir.resolve("skills"));
            if (!validSnapshot(agentId, identity, settings, children, skills)) {
                return null;
            }
            AgentRuntime metadata = toRuntime(identity, settings, systemPrompt, children, skills);
            return new PreparedAgentRuntime(agentId, agentRoot.toAbsolutePath().normalize(), metadata, skills);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private List<AgentReference> loadChildren(Path agentsDirectory) throws IOException {
        if (!isSafeDirectory(agentsDirectory)) {
            throw new IOException("Child Agent directory is unavailable");
        }
        List<AgentReference> children = new ArrayList<>();
        try (var entries = Files.list(agentsDirectory)) {
            for (Path file : entries.sorted().toList()) {
                ChildManifest child = readJson(file, ChildManifest.class);
                String fileName = file.getFileName().toString();
                if (child.schemaVersion() != SCHEMA_VERSION || !fileName.equals(child.name() + ".json")) {
                    throw new IOException("Child Agent name does not match its path");
                }
                children.add(new AgentReference(
                        child.agentId(),
                        child.name(),
                        child.displayName(),
                        child.description(),
                        child.version(),
                        child.enabled()));
            }
        }
        return List.copyOf(children);
    }

    private List<SkillInfo> loadSkills(Path skillsDirectory) throws IOException {
        if (!isSafeDirectory(skillsDirectory)) {
            throw new IOException("Skill directory is unavailable");
        }
        List<SkillInfo> skills = new ArrayList<>();
        try (var entries = Files.list(skillsDirectory)) {
            for (Path directory : entries.sorted().toList()) {
                skills.add(loadSkill(directory));
            }
        }
        return List.copyOf(skills);
    }

    private SkillInfo loadSkill(Path directory) throws IOException {
        if (!isSafeDirectory(directory)
                || !isSafeDirectory(directory.resolve("references"))
                || !isSafeDirectory(directory.resolve("templates"))) {
            throw new IOException("Skill directory is incomplete");
        }
        SkillManifest manifest = readJson(directory.resolve(SKILL_MANIFEST_FILE), SkillManifest.class);
        if (manifest.schemaVersion() != SCHEMA_VERSION
                || !directory.getFileName().toString().equals(manifest.name())) {
            throw new IOException("Skill name does not match its path");
        }
        Path skillFile = directory.resolve(SKILL_FILE);

        // 缓存读取与发布前复核共用同一校验入口(字节上限 + SkillLoader 完整规则):
        // 任一规则不过即判缓存不完整,触发重新拉取,而不是带着缺陷命中缓存。
        Skill loaded = requireSessionLoadable(manifest.name(), skillFile);
        String skillMarkdown = readRequiredFile(skillFile);
        return new SkillInfo(
                loaded.name(),
                manifest.id(),
                manifest.version(),
                loaded.description(),
                null,
                skillMarkdown,
                List.of(),
                List.<DependentSkill>of(),
                List.of(),
                List.of());
    }

    private void publish(String agentId, Path agentRoot, Path staging) throws IOException {
        Files.createDirectories(agentRoot);
        if (Files.isSymbolicLink(agentRoot)) {
            throw new IOException("Agent root must not be a symbolic link");
        }
        Path target = agentRoot.resolve(CAMPUSCLAW_DIRECTORY);
        Path backup = agentRoot.resolve(".campusclaw-backup-" + UUID.randomUUID());
        boolean hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        try {
            if (hadTarget) {
                moveAtomically(target, backup);
            }
            moveAtomically(staging, target);
        } catch (IOException exception) {
            rollbackPublish(target, backup, hadTarget);
            throw exception;
        }
        deleteQuietly(backup);
    }

    private static void rollbackPublish(Path target, Path backup, boolean hadTarget) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(target);
        }
        if (hadTarget && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            moveAtomically(backup, target);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic Agent runtime publication is not supported", exception);
        }
    }

    private List<SkillInfo> resolveSkills(List<SkillReference> references) {
        List<SkillInfo> skills = new ArrayList<>();
        for (SkillReference reference : references == null ? List.<SkillReference>of() : references) {
            SkillInfo skill = mateServiceClient.querySkillInfo(reference.id());
            requireValidSkill(skill, reference);
            skills.add(skill);
        }
        return List.copyOf(skills);
    }

    private Path requireAgentRoot(String agentId) {
        if (!matches(agentId, ResourceIdentifierPatterns.AGENT_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid agentId");
        }
        return properties
                .agentsRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(agentId)
                .normalize();
    }

    private <T> T withAgentLock(String agentId, SupplierWithException<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(agentId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static void requireValidRuntime(AgentRuntime runtime, String requestedId) {
        if (runtime == null
                || !requestedId.equals(runtime.id())
                || !matches(runtime.id(), ResourceIdentifierPatterns.AGENT_ID_PATTERN)) {
            throw new AgentRuntimeException("Mate returned an invalid Agent identity");
        }
        if (!isSafeName(runtime.name()) || isBlank(runtime.version()) || !validModels(runtime.bindingModels())) {
            throw new AgentRuntimeException("Mate returned invalid Agent metadata");
        }
        runtime.bindingSkills().forEach(AgentRuntimeManager::requireValidSkillReference);
        runtime.bindingAgents().forEach(AgentRuntimeManager::requireValidChildReference);
    }

    private static void requireValidSkill(SkillInfo skill, SkillReference reference) {
        requireIdentifier(skill == null ? null : skill.id(), ResourceIdentifierPatterns.SKILL_ID_PATTERN);
        if (!skill.id().equals(reference.id())) {
            throw new AgentRuntimeException("Mate returned a different Skill identity");
        }
        if (isBlank(reference.version()) || !reference.version().equals(skill.version())) {
            throw new AgentRuntimeException("Mate returned a different Skill version");
        }
        if (!isSafeName(skill.name()) || isBlank(skill.version())) {
            throw new AgentRuntimeException("Mate returned invalid Skill metadata");
        }
    }

    private static boolean validSnapshot(
            String agentId,
            AgentIdentity identity,
            AgentSettings settings,
            List<AgentReference> children,
            List<SkillInfo> skills) {
        if (!validIdentity(agentId, identity) || !validSettings(settings)) {
            return false;
        }
        return uniqueNames(children.stream().map(AgentReference::name).toList())
                && uniqueNames(skills.stream().map(SkillInfo::name).toList())
                && skills.stream().allMatch(AgentRuntimeManager::validCachedSkill)
                && children.stream().allMatch(AgentRuntimeManager::validCachedChild);
    }

    private static boolean validIdentity(String agentId, AgentIdentity identity) {
        return identity != null
                && identity.schemaVersion() == SCHEMA_VERSION
                && agentId.equals(identity.id())
                && validIdentityFields(identity);
    }

    private static boolean validIdentityFields(AgentIdentity identity) {
        return matches(identity.id(), ResourceIdentifierPatterns.AGENT_ID_PATTERN)
                && isSafeName(identity.name())
                && !isBlank(identity.version())
                && identity.enabled() != null;
    }

    private static boolean validSettings(AgentSettings settings) {
        return settings != null
                && settings.schemaVersion() == SCHEMA_VERSION
                && validModels(settings.bindingModels())
                && validDefaultModel(settings);
    }

    private static boolean validDefaultModel(AgentSettings settings) {
        return settings.defaultModel() == null
                ? settings.bindingModels().isEmpty()
                : settings.bindingModels().contains(settings.defaultModel());
    }

    private static boolean validModels(List<String> models) {
        Set<String> unique = new HashSet<>();
        return models != null && models.stream().allMatch(model -> !isBlank(model) && unique.add(model));
    }

    private static boolean validCachedSkill(SkillInfo skill) {
        return skill != null
                && matches(skill.id(), ResourceIdentifierPatterns.SKILL_ID_PATTERN)
                && isSafeName(skill.name())
                && !isBlank(skill.version());
    }

    private static boolean validCachedChild(AgentReference child) {
        return child != null
                && matches(child.id(), ResourceIdentifierPatterns.AGENT_ID_PATTERN)
                && isSafeName(child.name())
                && !isBlank(child.version())
                && child.enabled() != null;
    }

    private static boolean uniqueNames(List<String> names) {
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (String name : names) {
            if (!isSafeName(name) || !exact.add(name) || !folded.add(name.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static void requireSafeUniqueName(String name, Set<String> names, String type) {
        if (!isSafeName(name) || !names.add(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new AgentRuntimeException(type + " name is invalid or duplicated");
        }
    }

    private static boolean isSafeName(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")) {
            return false;
        }
        return value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.indexOf('\0') < 0;
    }

    private static boolean isSafeDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private static boolean containsForbiddenEntry(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path ->
                    Files.isSymbolicLink(path) || path.getFileName().toString().equals("tools.json"));
        } catch (IOException exception) {
            return true;
        }
    }

    private <T> T readJson(Path path, Class<T> type) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Managed JSON file is unavailable");
        }
        return mapper.readValue(path.toFile(), type);
    }

    private static String readRequiredFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Managed file is unavailable");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void writeJson(Path path, Object value) throws IOException {
        writeFile(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private static String resourceFileName(SkillFile resource) {
        String type = resource.fileType() == null ? "" : resource.fileType().toLowerCase(java.util.Locale.ROOT);
        return resource.name() + (type.isBlank() ? "" : "." + type);
    }

    private static AgentIdentity toIdentity(AgentRuntime runtime) {
        return new AgentIdentity(
                SCHEMA_VERSION,
                runtime.id(),
                runtime.name(),
                runtime.displayName(),
                runtime.description(),
                runtime.version(),
                runtime.enabled());
    }

    private static AgentSettings toSettings(AgentRuntime runtime) {
        String defaultModel = runtime.defaultModel().orElse(null);
        return new AgentSettings(SCHEMA_VERSION, defaultModel, runtime.bindingModels());
    }

    private static AgentRuntime toRuntime(
            AgentIdentity identity,
            AgentSettings settings,
            String systemPrompt,
            List<AgentReference> children,
            List<SkillInfo> skills) {
        List<SkillReference> skillReferences = skills.stream()
                .map(skill -> new SkillReference(skill.id(), skill.version()))
                .toList();
        return new AgentRuntime(
                settings.bindingModels(),
                skillReferences,
                List.of(),
                children,
                identity.description(),
                identity.displayName(),
                identity.enabled(),
                identity.id(),
                identity.name(),
                systemPrompt,
                List.of(),
                identity.version());
    }

    private static void requireIdentifier(String value, Pattern pattern) {
        if (!matches(value, pattern)) {
            throw new AgentRuntimeException("Mate returned an invalid typed resource ID");
        }
    }

    private static void requireValidSkillReference(SkillReference reference) {
        requireIdentifier(reference == null ? null : reference.id(), ResourceIdentifierPatterns.SKILL_ID_PATTERN);
        if (isBlank(reference.version())) {
            throw new AgentRuntimeException("Mate returned a Skill binding without a fixed version");
        }
    }

    private static void requireValidChildReference(AgentReference child) {
        requireIdentifier(child == null ? null : child.id(), ResourceIdentifierPatterns.AGENT_ID_PATTERN);
        if (!isSafeName(child.name()) || isBlank(child.version()) || child.enabled() == null) {
            throw new AgentRuntimeException("Mate returned invalid Child Agent metadata");
        }
    }

    private static boolean matches(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException exception) {
            LOGGER.debug("Failed to clean managed Agent staging path: {}", path, exception);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record AgentIdentity(
            int schemaVersion,
            String id,
            String name,
            String displayName,
            List<String> description,
            String version,
            Boolean enabled) {}

    private record AgentSettings(int schemaVersion, String defaultModel, List<String> bindingModels) {
        private AgentSettings {
            bindingModels = bindingModels == null ? List.of() : List.copyOf(bindingModels);
        }
    }

    private record ChildManifest(
            int schemaVersion,
            String agentId,
            String name,
            String displayName,
            String description,
            String version,
            Boolean enabled) {}

    private record SkillManifest(int schemaVersion, String id, String name, String version) {}

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get();
    }
}
