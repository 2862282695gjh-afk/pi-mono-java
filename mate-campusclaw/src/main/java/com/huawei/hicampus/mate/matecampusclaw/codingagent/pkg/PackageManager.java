/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nullable;

/**
 * 发现并管理只包含 Skill 的本地包。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class PackageManager {
    private static final Logger log = LoggerFactory.getLogger(PackageManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("checkstyle:top_class_comment")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackageManifest(
            @JsonProperty("name") String name,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("skills") @Nullable List<String> skills,
            @JsonProperty("repository") @Nullable String repository) {}

    @SuppressWarnings("checkstyle:top_class_comment")
    public record InstalledPackage(String name, String version, Path location, PackageManifest manifest) {}

    private final Path packagesDir;
    private final Map<String, InstalledPackage> installed = new LinkedHashMap<>();

    public PackageManager(Path packagesDir) {
        this.packagesDir = packagesDir;
    }

    /**
     * 扫描本地 Skill 包目录。
     */
    public void scan() {
        installed.clear();
        if (!Files.isDirectory(packagesDir)) {
            return;
        }

        try (var dirs = Files.list(packagesDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path manifestPath = dir.resolve("package.json");
                if (Files.exists(manifestPath)) {
                    try {
                        PackageManifest manifest = MAPPER.readValue(manifestPath.toFile(), PackageManifest.class);
                        String name = manifest.name() != null
                                ? manifest.name()
                                : dir.getFileName().toString();
                        String version = manifest.version() != null ? manifest.version() : "0.0.0";
                        installed.put(name, new InstalledPackage(name, version, dir, manifest));
                        log.debug("Found package: {} v{}", name, version);
                    } catch (IOException e) {
                        log.warn("Failed to read package manifest: {}", manifestPath, e);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan packages directory: {}", packagesDir, e);
        }
    }

    /**
     * 返回全部已发现 Skill 包。
     *
     * @return 不可变 Skill 包列表
     */
    public List<InstalledPackage> getInstalled() {
        return List.copyOf(installed.values());
    }

    /**
     * 按名称返回一个已发现 Skill 包。
     *
     * @param name 包名称
     * @return 对应 Skill 包
     */
    public Optional<InstalledPackage> get(String name) {
        return Optional.ofNullable(installed.get(name));
    }

    /**
     * 判断指定 Skill 包是否存在。
     *
     * @param name 包名称
     * @return 存在时为 true
     */
    public boolean isInstalled(String name) {
        return installed.containsKey(name);
    }

    /**
     * 返回全部 Skill 文档路径。
     *
     * @return Skill 文档路径
     */
    public List<Path> getAllSkillPaths() {
        List<Path> paths = new ArrayList<>();
        for (InstalledPackage pkg : installed.values()) {
            if (pkg.manifest().skills() != null) {
                for (String skill : pkg.manifest().skills()) {
                    paths.add(pkg.location().resolve(skill));
                }
            }

            // 同时支持包内约定的 skills/ 目录。
            Path skillsDir = pkg.location().resolve("skills");
            if (Files.isDirectory(skillsDir)) {
                try (var stream = Files.list(skillsDir)) {
                    stream.filter(p -> p.toString().endsWith(".md")).forEach(paths::add);
                } catch (IOException e) {
                    log.debug("Failed to list skills dir for {}", pkg.name(), e);
                }
            }
        }
        return paths;
    }
}
