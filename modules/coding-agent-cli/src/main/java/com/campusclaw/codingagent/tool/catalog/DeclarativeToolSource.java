/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Tool source backed by user/project declarative tool definitions.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class DeclarativeToolSource implements ToolSource {

    private final ToolDeclarationLoader loader;

    public DeclarativeToolSource(ToolDeclarationLoader loader) {
        this.loader = loader;
    }

    @Override
    public List<ToolContribution> load(ToolSourceContext context) {
        var contributions = new ArrayList<ToolContribution>();
        contributions.addAll(
                loadDirectory(context.userToolsDir(), ToolContributionSource.user("user-tools"), 300, context));
        contributions.addAll(loadDirectory(
                context.projectToolsDir(), ToolContributionSource.project("project-tools"), 400, context));
        return List.copyOf(contributions);
    }

    private List<ToolContribution> loadDirectory(
            Path directory, ToolContributionSource source, int priority, ToolSourceContext context) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(this::isDeclarationFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> toContribution(path, source, priority, context))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to scan " + directory + ": " + e.getMessage(), e);
        }
    }

    private boolean isDeclarationFile(Path path) {
        String fileName = path.getFileName().toString();
        return Files.isRegularFile(path)
                && (fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json"));
    }

    private ToolContribution toContribution(
            Path path, ToolContributionSource source, int priority, ToolSourceContext context) {
        var declaration = loader.load(path);
        var tool = new ProcessAgentTool(declaration, context.cwd());
        return switch (declaration.mergeStrategy()) {
            case ADD -> ToolContribution.add(tool, source, priority);
            case REPLACE -> ToolContribution.replace(tool, source, priority, declaration.replaces());
            case WRAP -> throw new IllegalArgumentException("declarative tools do not support WRAP merge strategy");
            case DISABLE -> ToolContribution.disable(declaration.name(), source, priority);
        };
    }
}
