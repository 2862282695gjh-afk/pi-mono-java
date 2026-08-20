/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

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

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.DependentSkill;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.session.SessionConfig;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * 从本地缓存或 CampusMate 解析托管 CLI Agent，并物化现有 Skill 加载器需要的目录结构。
 *
 * <p>路径解析和物化前校验 Agent、Skill、Tool 类型化资源 ID，防止旧格式或路径分隔符进入快照。
 * 其余快照加固仍按 {@code docs/DEFERRED.md} 的 DEF-008 暂缓：当前不校验符号链接、本地篡改、
 * 配置漂移和完整响应形状，也不执行原子发布。无法读取的本地快照会重新物化。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class AgentRuntimeManager {

    /** 类型化 Agent UUID，不允许路径分隔符或旧格式。 */
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile(RuntimeApiConstants.AGENT_ID_PATTERN);

    /** 类型化 Skill UUID，不接受旧式短标识。 */
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(RuntimeApiConstants.SKILL_ID_PATTERN);

    /** 类型化 Tool UUID，不接受旧式短标识。 */
    private static final Pattern TOOL_ID_PATTERN = Pattern.compile(RuntimeApiConstants.TOOL_ID_PATTERN);

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
     * 本地快照可用时直接加载，否则从 CampusMate 获取并物化目录。
     * 无法读取的快照会进入重新物化，而不是按 fail closed 拒绝。
     * 准备过程只按 Agent ID 串行，因此不同 Agent 的冷启动互不阻塞。
     *
     * @param agentId 已选择的 Agent 标识
     * @return 不可变的已准备运行时
     * @throws AgentRuntimeException 获取或物化运行时失败时抛出
     * @throws IllegalArgumentException {@code agentId} 为空或不符合单路径段格式时抛出
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
     * 只加载本地缓存，不发起远端请求，也不物化目录。
     *
     * @param agentId 已选择的 Agent 标识
     * @return 缓存运行时；没有完整快照时返回 {@code null}
     * @throws IllegalArgumentException {@code agentId} 为空或不符合单路径段格式时抛出
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
        requireValidRuntimeIdentifiers(remote);
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
     * 根据已缓存的 Agent 元数据派生托管 Session 配置。
     *
     * @param base CLI 基础配置
     * @param runtime 已准备运行时
     * @return 当前 Agent 的 Session 配置
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
     * 加载某个已绑定 Skill 的全部持久化工具名。
     *
     * @param runtime 已准备的 Agent 运行时
     * @param skillName 已绑定 Skill 名称
     * @return 按快照顺序排列的 Skill 工具名
     * @throws AgentRuntimeException 无法读取 Skill 或工具快照时抛出
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
        if (!hasValidRuntimeIdentifiers(metadata)) {
            return null;
        }
        List<SkillInfo> skills = loadBoundSkills(skillsDir, metadata.bindingSkills());
        return skills == null ? null : new PreparedAgentRuntime(agentId, agentRoot, metadata, skills);
    }

    /**
     * 从已物化 SKILL.md 的 frontmatter 重建绑定 Skill 列表。
     * 包含可解析 SKILL.md 的子目录即表示 Agent 绑定了该 Skill；声明数量只用于识别不完整目录，
     * 不完整目录会进入重新物化。
     *
     * @param skillsDir 每个已绑定 Skill 对应一个子目录的目录
     * @param references 缓存 Agent 元数据声明的绑定引用
     * @return 按名称排序的绑定 Skill；快照不完整时返回 {@code null}
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
     * 从 SKILL.md frontmatter 重建最小 Skill 身份快照。
     *
     * @param content SKILL.md 内容
     * @return 只包含名称和描述的 Skill 元数据；缺少可用名称或描述时返回 {@code null}
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

        // skills 目录完全受管；重新物化时从头重建，避免旧绑定目录残留。
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
     * 渲染与 Agent 元数据一同保存的模型选择配置。
     * {@code agentVersion} 可解析为整数时保留数字形式，否则保留原始字符串。
     *
     * @param runtime Agent 元数据
     * @return setting.json 内容
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
     * 渲染 SKILL.md；其 frontmatter 中的 {@code name} 和 {@code description}
     * 是 Skill 身份的唯一持久化来源。不保存 Skill ID，已物化的 {@code skills/}
     * 子目录本身表示 Agent 绑定了哪些 Skill。
     *
     * @param skill 从 CampusMate 获取的 Skill 元数据
     * @return SKILL.md 内容
     * @throws IOException 无法对描述执行 JSON 转义时抛出
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
        List<SkillTool> tools =
                mapper.readValue(toolsFile.toFile(), SkillToolsFile.class).tools();
        for (SkillTool tool : tools) {
            requireIdentifier(tool.toolId(), TOOL_ID_PATTERN, "toolId");
        }
        return tools;
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
            SkillInfo skill = result.getFirst();
            requireValidSkillIdentifiers(skill);
            skills.add(skill);
        }
        return List.copyOf(skills);
    }

    private static void requireValidRuntimeIdentifiers(AgentRuntime runtime) {
        if (!hasValidRuntimeIdentifiers(runtime)) {
            throw new AgentRuntimeException("GetAgentRuntime returned an invalid typed resource ID");
        }
    }

    private static boolean hasValidRuntimeIdentifiers(AgentRuntime runtime) {
        if (runtime == null || !matches(runtime.id(), AGENT_ID_PATTERN)) {
            return false;
        }
        boolean skillsValid = runtime.bindingSkills().stream()
                .allMatch(reference -> reference != null && matches(reference.id(), SKILL_ID_PATTERN));
        boolean toolsValid =
                runtime.bindingTools().stream().allMatch(tool -> tool != null && matches(tool.id(), TOOL_ID_PATTERN));
        boolean agentsValid = runtime.bindingAgents().stream()
                .allMatch(agent -> agent != null && matches(agent.id(), AGENT_ID_PATTERN));
        return skillsValid && toolsValid && agentsValid;
    }

    private static void requireValidSkillIdentifiers(SkillInfo skill) {
        requireIdentifier(skill == null ? null : skill.id(), SKILL_ID_PATTERN, "skillId");
        for (BoundTool tool : skill.bindingTools()) {
            requireIdentifier(tool == null ? null : tool.id(), TOOL_ID_PATTERN, "toolId");
        }
        for (DependentSkill dependency : skill.bindingSkills()) {
            requireIdentifier(dependency == null ? null : dependency.id(), SKILL_ID_PATTERN, "skillId");
        }
    }

    private static void requireIdentifier(String value, Pattern pattern, String name) {
        if (!matches(value, pattern)) {
            throw new AgentRuntimeException("Invalid " + name + ": " + value);
        }
    }

    private static boolean matches(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
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
