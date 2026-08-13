/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillTools;
import com.campusclaw.codingagent.session.SessionConfig;
import com.campusclaw.codingagent.skill.Skill;
import com.campusclaw.codingagent.skill.SkillLoadException;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Resolves managed Agent runtimes from the local cache or CampusMate and materializes
 * the directory structure expected by the existing Skill loader.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/10]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class AgentRuntimeManager {

    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern SKILL_ID_PATTERN = AGENT_ID_PATTERN;
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile(Skill.NAME_PATTERN);
    private static final Pattern RESOURCE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Set<String> RESOURCE_FILE_TYPES = Set.of("md", "txt");
    private static final String AGENT_METADATA_FILE = "agentId.json";
    private static final String SKILL_METADATA_FILE = "skill.json";
    private static final int MAX_BOUND_SKILLS = 128;
    private static final int MAX_RESOURCES_PER_SKILL = 256;
    private static final int MAX_RESOURCE_BYTES = 1024 * 1024;
    private static final long MAX_SKILL_RESOURCE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_AGENT_RESOURCE_BYTES = 64L * 1024 * 1024;

    private final AgentRuntimeProperties properties;
    private final MateServiceClient mateServiceClient;
    private final ObjectMapper mapper;
    private final SkillLoader skillLoader = new SkillLoader();

    public AgentRuntimeManager(
            AgentRuntimeProperties properties, MateServiceClient mateServiceClient, ObjectMapper mapper) {
        this.properties = properties;
        this.mateServiceClient = mateServiceClient;
        this.mapper = mapper;
    }

    /**
     * Loads a complete local Agent runtime, fetching and materializing it only when the
     * Agent directory is absent. Existing incomplete directories fail closed.
     *
     * @param agentId selected Agent identifier
     * @return immutable prepared runtime
     * @throws AgentRuntimeException when the runtime cannot be fetched, validated, or materialized
     */
    public synchronized PreparedAgentRuntime prepare(String agentId) {
        validateAgentId(agentId);
        Path agentsRoot = properties.agentsRoot().toAbsolutePath().normalize();
        Path agentRoot = agentsRoot.resolve(agentId).normalize();
        ensureWithin(agentsRoot, agentRoot);
        rejectSymbolicLinks(agentsRoot, agentRoot);

        PreparedAgentRuntime local = loadIfComplete(agentId, agentRoot);
        if (local != null) {
            return local;
        }
        if (Files.exists(agentRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentRuntimeException("Existing Agent runtime is incomplete: " + agentId);
        }

        AgentRuntime remote = mateServiceClient.getAgentRuntime(agentId);
        validateRuntime(agentId, remote);
        List<SkillInfo> skills = resolveSkills(remote.bindingSkills());
        materialize(agentRoot, remote, skills);

        PreparedAgentRuntime prepared = loadIfComplete(agentId, agentRoot);
        if (prepared == null) {
            throw new AgentRuntimeException("Agent runtime materialization is incomplete: " + agentId);
        }
        return prepared;
    }

    /**
     * Derives a managed session configuration from cached Agent metadata.
     *
     * @param base base CLI/server configuration
     * @param runtime prepared runtime
     * @return per-Agent session configuration
     */
    public SessionConfig sessionConfig(SessionConfig base, PreparedAgentRuntime runtime) {
        String runtimeModel = runtime.metadata().bindingModels();
        String model = runtimeModel != null && !runtimeModel.isBlank() ? runtimeModel : base.model();
        String prompt = joinPrompts(runtime.metadata().systemPrompt(), base.customPrompt());
        return new SessionConfig(model, runtime.agentRoot(), prompt, base.mode());
    }

    /**
     * Queries CampusMate after a Skill is selected and intersects the response with the
     * Skill's {@code permission=allow} metadata.
     *
     * @param runtime prepared Agent runtime
     * @param skillName selected Skill name
     * @return permitted tool names in response order
     * @throws AgentRuntimeException when the Skill is not bound or the query result is invalid
     */
    public List<String> queryAllowedSkillToolNames(PreparedAgentRuntime runtime, String skillName) {
        SkillInfo skill = runtime.findSkill(skillName)
                .orElseThrow(() -> new AgentRuntimeException(
                        "Skill is not bound to Agent " + runtime.agentId() + ": " + skillName));
        List<MateServiceClient.BoundTool> allowedByMetadata = skill.bindingTools().stream()
                .filter(tool -> "allow".equalsIgnoreCase(tool.permission()))
                .toList();
        List<SkillTools> matches = mateServiceClient.querySkillTools(List.of(skillName)).stream()
                .filter(entry -> skillName.equals(entry.skillName()))
                .toList();
        if (matches.size() != 1) {
            throw new AgentRuntimeException("querySkillTools must return exactly one entry for Skill: " + skillName);
        }
        SkillTools queried = matches.getFirst();
        return queried.toolList().stream()
                .filter(reference -> allowedByMetadata.stream().anyMatch(tool -> sameTool(tool, reference)))
                .map(MateServiceClient.ToolReference::toolName)
                .distinct()
                .toList();
    }

    private PreparedAgentRuntime loadIfComplete(String agentId, Path agentRoot) {
        Path campusClawDir = agentRoot.resolve(".campusclaw");
        Path metadataFile = campusClawDir.resolve(AGENT_METADATA_FILE);
        Path skillsDir = campusClawDir.resolve("skills");
        rejectSymbolicLinks(properties.agentsRoot().toAbsolutePath().normalize(), skillsDir);
        if (!Files.isDirectory(agentRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        AgentRuntime metadata;
        try {
            metadata = mapper.readValue(metadataFile.toFile(), AgentRuntime.class);
        } catch (IOException e) {
            return null;
        }
        try {
            validateRuntime(agentId, metadata);
        } catch (AgentRuntimeException e) {
            return null;
        }
        List<SkillInfo> skills;
        try {
            skills = loadBoundSkills(skillsDir, metadata.bindingSkills());
        } catch (AgentRuntimeException e) {
            return null;
        }
        return skills == null ? null : new PreparedAgentRuntime(agentId, agentRoot, metadata, skills);
    }

    private List<SkillInfo> loadBoundSkills(Path skillsDir, List<SkillReference> references) {
        List<SkillInfo> skills = new java.util.ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        long resourceBytes = 0L;
        for (SkillReference reference : references) {
            SkillInfo skill = findLocalSkill(skillsDir, reference);
            if (skill == null || !ids.add(skill.id()) || !names.add(skill.name())) {
                return null;
            }
            resourceBytes += validateSkillInfo(skill);
            if (resourceBytes > MAX_AGENT_RESOURCE_BYTES) {
                return null;
            }
            skills.add(skill);
        }
        return hasOnlyExpectedSkillDirectories(skillsDir, names) ? List.copyOf(skills) : null;
    }

    private static boolean hasOnlyExpectedSkillDirectories(Path skillsDir, Set<String> expectedNames) {
        Set<String> actualNames = new HashSet<>();
        try (var entries = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        || !actualNames.add(entry.getFileName().toString())) {
                    return false;
                }
            }
            return actualNames.equals(expectedNames);
        } catch (IOException e) {
            return false;
        }
    }

    private SkillInfo findLocalSkill(Path skillsDir, SkillReference reference) {
        try (var entries = Files.newDirectoryStream(skillsDir)) {
            SkillInfo match = null;
            for (Path skillDir : entries) {
                if (!Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path skillMetadataFile = skillDir.resolve(SKILL_METADATA_FILE);
                if (!Files.isRegularFile(skillMetadataFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                SkillInfo skill = mapper.readValue(skillMetadataFile.toFile(), SkillInfo.class);
                if (sameSkill(reference, skill)) {
                    if (match != null || !isCompleteSkill(skillDir, skill)) {
                        return null;
                    }
                    match = skill;
                }
            }
            return match;
        } catch (IOException | AgentRuntimeException e) {
            return null;
        }
    }

    private boolean isCompleteSkill(Path skillDir, SkillInfo metadata) {
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)
                || !skillDir.getFileName().toString().equals(metadata.name())
                || !Files.isRegularFile(skillDir.resolve(SKILL_METADATA_FILE), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(skillDir.resolve("references"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(skillDir.resolve("templates"), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            validateSkillInfo(metadata);
            return Files.readString(skillFile, StandardCharsets.UTF_8).equals(renderSkill(metadata))
                    && metadata.name()
                            .equals(skillLoader
                                    .loadFromFile(skillFile, "project")
                                    .name())
                    && hasCompleteResources(skillDir.resolve("references"), metadata.references())
                    && hasCompleteResources(skillDir.resolve("templates"), metadata.templates());
        } catch (IOException | SkillLoadException | AgentRuntimeException e) {
            return false;
        }
    }

    private boolean hasCompleteResources(Path directory, List<SkillFile> resources) {
        try {
            Set<String> expected = new HashSet<>();
            for (SkillFile resource : resources) {
                String fileName = resourceFileName(resource);
                Path file = directory.resolve(fileName);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !Objects.equals(Files.readString(file, StandardCharsets.UTF_8), resource.content())) {
                    return false;
                }
                expected.add(fileName.toLowerCase(Locale.ROOT));
            }
            try (var entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            || !expected.remove(entry.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                }
            }
            return expected.isEmpty();
        } catch (IOException | AgentRuntimeException e) {
            return false;
        }
    }

    private void materialize(Path agentRoot, AgentRuntime runtime, List<SkillInfo> skills) {
        Path agentsRoot = properties.agentsRoot().toAbsolutePath().normalize();
        try {
            Files.createDirectories(agentsRoot);
            rejectSymbolicLinks(agentsRoot, agentRoot);
            if (Files.exists(agentRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentRuntimeException("Agent runtime appeared during materialization: " + runtime.id());
            }
            materializeNewAgentAtomically(agentsRoot, agentRoot, runtime, skills);
        } catch (IOException e) {
            throw new AgentRuntimeException("Failed to materialize Agent runtime: " + runtime.id(), e);
        }
    }

    private void materializeNewAgentAtomically(
            Path agentsRoot, Path agentRoot, AgentRuntime runtime, List<SkillInfo> skills) throws IOException {
        Path stage = Files.createTempDirectory(agentsRoot, "." + runtime.id() + "-");
        boolean moved = false;
        try {
            writeRuntimeTree(stage, runtime, skills);
            if (loadIfComplete(runtime.id(), stage) == null) {
                throw new AgentRuntimeException(
                        "Generated Agent runtime failed validation before publication: " + runtime.id());
            }
            moveDirectory(stage, agentRoot);
            moved = true;
        } finally {
            if (!moved) {
                deleteTree(stage);
            }
        }
    }

    private void writeRuntimeTree(Path agentRoot, AgentRuntime runtime, List<SkillInfo> skills) throws IOException {
        Path campusClawDir = agentRoot.resolve(".campusclaw");
        Path skillsDir = campusClawDir.resolve("skills");
        Files.createDirectories(skillsDir);
        for (SkillInfo skill : skills) {
            Path skillDir = skillsDir.resolve(skill.name());
            Path referencesDir = skillDir.resolve("references");
            Path templatesDir = skillDir.resolve("templates");
            Files.createDirectories(referencesDir);
            Files.createDirectories(templatesDir);
            writeResources(referencesDir, skill.references());
            writeResources(templatesDir, skill.templates());
            writeAtomically(skillDir.resolve("SKILL.md"), renderSkill(skill));
            writeAtomically(skillDir.resolve(SKILL_METADATA_FILE), mapper.writeValueAsString(skill));
        }
        writeAtomically(campusClawDir.resolve(AGENT_METADATA_FILE), mapper.writeValueAsString(runtime));
    }

    private void writeResources(Path directory, List<SkillFile> resources) throws IOException {
        Set<String> targets = new HashSet<>();
        for (SkillFile resource : resources) {
            String targetName = resourceFileName(resource);
            if (!targets.add(targetName.toLowerCase(Locale.ROOT))) {
                throw new AgentRuntimeException("Duplicate Skill resource file: " + targetName);
            }
            writeAtomically(directory.resolve(targetName), resource.content());
        }
    }

    private static String resourceFileName(SkillFile resource) {
        if (resource == null) {
            throw new AgentRuntimeException("Invalid null Skill resource");
        }
        String name = resource.name();
        String type = resource.fileType() == null ? "" : resource.fileType().toLowerCase(Locale.ROOT);
        if (name == null
                || !name.equals(name.trim())
                || !RESOURCE_NAME_PATTERN.matcher(name).matches()
                || name.equals(".")
                || name.equals("..")
                || !RESOURCE_FILE_TYPES.contains(type)) {
            throw new AgentRuntimeException("Invalid Skill resource name or type: " + name);
        }
        return name + "." + type;
    }

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

    private List<SkillInfo> resolveSkills(List<SkillReference> references) {
        List<SkillInfo> skills = new java.util.ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> names = new HashSet<>();
        long resourceBytes = 0L;
        for (SkillReference reference : references) {
            validateSkillReference(reference);
            if (!ids.add(reference.id())) {
                throw new AgentRuntimeException("Duplicate Skill id in Agent runtime: " + reference.id());
            }
            List<SkillInfo> result = mateServiceClient.querySkillInfo(reference.id());
            if (result.size() != 1) {
                throw new AgentRuntimeException(
                        "querySkillInfo must return exactly one Skill for id " + reference.id());
            }
            SkillInfo skill = result.getFirst();
            resourceBytes += validateSkillInfo(skill);
            if (resourceBytes > MAX_AGENT_RESOURCE_BYTES) {
                throw new AgentRuntimeException("Agent Skill resources exceed the allowed size");
            }
            if (!sameSkill(reference, skill)) {
                throw new AgentRuntimeException("querySkillInfo returned mismatched Skill for id " + reference.id());
            }
            if (!names.add(skill.name())) {
                throw new AgentRuntimeException("Duplicate Skill name in Agent runtime: " + skill.name());
            }
            skills.add(skill);
        }
        return List.copyOf(skills);
    }

    private void validateRuntime(String requestedAgentId, AgentRuntime runtime) {
        if (runtime == null) {
            throw new AgentRuntimeException("GetAgentRuntime returned an empty result");
        }
        if (runtime.id() == null || runtime.id().isBlank()) {
            throw new AgentRuntimeException("GetAgentRuntime result is missing Agent id");
        }
        if (!requestedAgentId.equals(runtime.id())) {
            throw new AgentRuntimeException(
                    "GetAgentRuntime returned Agent " + runtime.id() + " for requested id " + requestedAgentId);
        }
        if (runtime.bindingSkills().size() > MAX_BOUND_SKILLS) {
            throw new AgentRuntimeException("Agent binds too many Skills");
        }
        Set<String> ids = new HashSet<>();
        for (SkillReference reference : runtime.bindingSkills()) {
            validateSkillReference(reference);
            if (!ids.add(reference.id())) {
                throw new AgentRuntimeException("Duplicate Skill id in Agent runtime: " + reference.id());
            }
        }
    }

    private static void validateSkillReference(SkillReference reference) {
        if (reference == null
                || reference.id() == null
                || !SKILL_ID_PATTERN.matcher(reference.id()).matches()
                || !hasText(reference.version())) {
            throw new AgentRuntimeException("Invalid Skill reference in Agent runtime");
        }
    }

    private static long validateSkillInfo(SkillInfo skill) {
        if (skill == null
                || skill.id() == null
                || !SKILL_ID_PATTERN.matcher(skill.id()).matches()
                || !hasText(skill.version())) {
            throw new AgentRuntimeException("Invalid Skill metadata");
        }
        validateSkillName(skill.name());
        if ((long) skill.references().size() + skill.templates().size() > MAX_RESOURCES_PER_SKILL) {
            throw new AgentRuntimeException("Skill has too many resource files: " + skill.name());
        }
        long resourceBytes = validateResources(skill.references()) + validateResources(skill.templates());
        if (resourceBytes > MAX_SKILL_RESOURCE_BYTES) {
            throw new AgentRuntimeException("Skill resources exceed the allowed size: " + skill.name());
        }
        return resourceBytes;
    }

    private static long validateResources(List<SkillFile> resources) {
        Set<String> targets = new HashSet<>();
        long resourceBytes = 0L;
        for (SkillFile resource : resources) {
            String fileName = resourceFileName(resource).toLowerCase(Locale.ROOT);
            if (!targets.add(fileName)) {
                throw new AgentRuntimeException("Duplicate Skill resource file: " + fileName);
            }
            if (resource.content() == null) {
                throw new AgentRuntimeException("Skill resource content is required: " + fileName);
            }
            int contentBytes = resource.content().getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > MAX_RESOURCE_BYTES) {
                throw new AgentRuntimeException("Skill resource exceeds the allowed size: " + fileName);
            }
            resourceBytes += contentBytes;
        }
        return resourceBytes;
    }

    private static boolean sameSkill(SkillReference reference, SkillInfo skill) {
        return Objects.equals(reference.id(), skill.id()) && Objects.equals(reference.version(), skill.version());
    }

    private static void validateAgentId(String agentId) {
        if (agentId == null || !AGENT_ID_PATTERN.matcher(agentId).matches()) {
            throw new IllegalArgumentException("Invalid agentId: " + agentId);
        }
    }

    private static void validateSkillName(String skillName) {
        if (skillName == null
                || skillName.length() > Skill.MAX_NAME_LENGTH
                || !SKILL_NAME_PATTERN.matcher(skillName).matches()) {
            throw new AgentRuntimeException("Invalid Skill name in Agent runtime: " + skillName);
        }
    }

    private static void ensureWithin(Path root, Path candidate) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Agent path escapes configured agents root");
        }
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

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean sameTool(MateServiceClient.BoundTool metadata, MateServiceClient.ToolReference reference) {
        if (!hasToolIdentity(metadata.id(), metadata.version(), metadata.name())
                || !hasToolIdentity(reference.toolId(), reference.toolVersion(), reference.toolName())) {
            return false;
        }
        return Objects.equals(metadata.id(), reference.toolId())
                && Objects.equals(metadata.version(), reference.toolVersion())
                && Objects.equals(metadata.name(), reference.toolName());
    }

    private static boolean hasToolIdentity(String id, String version, String name) {
        return hasText(id) && hasText(version) && hasText(name);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void rejectSymbolicLinks(Path root, Path candidate) {
        if (Files.isSymbolicLink(root)) {
            throw new AgentRuntimeException("Configured agents root must not be a symbolic link: " + root);
        }
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new AgentRuntimeException("Symbolic links are not allowed in Agent runtime paths: " + current);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
