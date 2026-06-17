/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.campusclaw.codingagent.settings.Settings;
import com.campusclaw.codingagent.settings.SettingsManager;
import com.campusclaw.codingagent.tool.catalog.ToolContribution;
import com.campusclaw.codingagent.tool.catalog.ToolContributionSource;
import com.campusclaw.codingagent.tool.catalog.ToolSource;
import com.campusclaw.codingagent.tool.catalog.ToolSourceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tool source backed by configured MCP servers.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class McpToolSource implements ToolSource {

    private static final Logger log = LoggerFactory.getLogger(McpToolSource.class);
    private static final Set<String> PROTECTED_BUILT_INS = Set.of("bash", "write", "edit", "read");
    private static final int MCP_PRIORITY = 250;
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_CALL_TIMEOUT_SECONDS = 60;

    private final Supplier<List<McpServerConfig>> configSupplier;
    private final McpClientFactory clientFactory;
    private final SettingsManager settingsManager;
    private final Map<McpServerConfig, McpClient> clients = new LinkedHashMap<>();

    public McpToolSource(DefaultMcpClientFactory clientFactory) {
        this(List.of(), clientFactory);
    }

    @Autowired
    public McpToolSource(SettingsManager settingsManager, McpClientFactory clientFactory) {
        this.settingsManager = settingsManager;
        this.clientFactory = clientFactory;
        this.configSupplier = () -> configsFromSettings(settingsManager.load());
    }

    public McpToolSource(List<McpServerConfig> configs, McpClientFactory clientFactory) {
        var staticConfigs = List.copyOf(configs != null ? configs : List.of());
        this.configSupplier = () -> staticConfigs;
        this.clientFactory = clientFactory;
        this.settingsManager = null;
    }

    @Override
    public synchronized List<ToolContribution> load(ToolSourceContext context) {
        if (context != null && !context.mcpEnabled()) {
            closeCachedClients();
            return List.of();
        }
        if (settingsManager != null && context != null) {
            settingsManager.setWorkingDir(context.cwd());
        }
        var configs =
                configSupplier.get().stream().filter(McpServerConfig::enabled).toList();
        closeStaleClients(configs);
        var contributions = new ArrayList<ToolContribution>();
        for (var config : configs) {
            var client = clients.computeIfAbsent(config, clientFactory::create);
            for (var definition : client.listTools()) {
                contributions.add(toContribution(config, definition, client));
            }
        }
        return List.copyOf(contributions);
    }

    private ToolContribution toContribution(McpServerConfig config, McpToolDefinition definition, McpClient client) {
        String exposedName = exposedName(config, definition.name());
        if (config.exposeNames() == McpServerConfig.ExposeNames.RAW
                && config.trust() != McpServerConfig.Trust.TRUSTED
                && PROTECTED_BUILT_INS.contains(exposedName)) {
            throw new IllegalArgumentException("untrusted MCP server '" + config.name()
                    + "' cannot expose protected raw tool name '" + exposedName + "'");
        }
        var tool = new McpAgentTool(exposedName, config.name() + " " + definition.name(), definition, client);
        return ToolContribution.add(tool, ToolContributionSource.mcp(config.name()), MCP_PRIORITY);
    }

    private void closeStaleClients(List<McpServerConfig> activeConfigs) {
        var active = Set.copyOf(activeConfigs);
        var iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!active.contains(entry.getKey())) {
                closeQuietly(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void closeCachedClients() {
        clients.values().forEach(this::closeQuietly);
        clients.clear();
    }

    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.debug("failed to close MCP client", e);
        }
    }

    private String exposedName(McpServerConfig config, String rawName) {
        if (config.exposeNames() == McpServerConfig.ExposeNames.RAW) {
            return rawName;
        }
        String prefix = config.namePrefix() != null ? config.namePrefix() : config.name() + "__";
        return prefix + rawName;
    }

    private static List<McpServerConfig> configsFromSettings(Settings settings) {
        if (settings == null
                || settings.tools() == null
                || settings.tools().mcpServers() == null
                || settings.tools().mcpServers().isEmpty()) {
            return List.of();
        }
        var configs = new ArrayList<McpServerConfig>();
        settings.tools().mcpServers().forEach((name, server) -> {
            if (name == null || name.isBlank() || server == null) {
                return;
            }
            configs.add(configFromSettings(name, server));
        });
        return List.copyOf(configs);
    }

    private static McpServerConfig configFromSettings(String name, Settings.McpServerSettings server) {
        McpServerConfig.Transport transport = parseTransport(server.transport(), server.url());
        return new McpServerConfig(
                name,
                server.enabled() == null || server.enabled(),
                transport,
                command(server),
                server.url(),
                server.env(),
                parseTrust(server.trust()),
                server.namePrefix(),
                parseExposeNames(server.exposeNames()),
                server.startupTimeoutSeconds() != null
                        ? server.startupTimeoutSeconds()
                        : DEFAULT_STARTUP_TIMEOUT_SECONDS,
                server.callTimeoutSeconds() != null ? server.callTimeoutSeconds() : DEFAULT_CALL_TIMEOUT_SECONDS);
    }

    private static List<String> command(Settings.McpServerSettings server) {
        var command = new ArrayList<String>();
        if (server.command() != null && !server.command().isBlank()) {
            command.add(server.command());
        }
        if (server.args() != null) {
            command.addAll(server.args());
        }
        return List.copyOf(command);
    }

    private static McpServerConfig.Transport parseTransport(String value, String url) {
        if (value == null || value.isBlank()) {
            return url != null && !url.isBlank() ? McpServerConfig.Transport.HTTP : McpServerConfig.Transport.STDIO;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "http" -> McpServerConfig.Transport.HTTP;
            case "stdio" -> McpServerConfig.Transport.STDIO;
            default -> throw new IllegalArgumentException("Unsupported MCP transport: " + value);
        };
    }

    private static McpServerConfig.Trust parseTrust(String value) {
        if (value == null || value.isBlank()) {
            return McpServerConfig.Trust.UNTRUSTED;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "trusted" -> McpServerConfig.Trust.TRUSTED;
            case "untrusted" -> McpServerConfig.Trust.UNTRUSTED;
            default -> throw new IllegalArgumentException("Unsupported MCP trust: " + value);
        };
    }

    private static McpServerConfig.ExposeNames parseExposeNames(String value) {
        if (value == null || value.isBlank()) {
            return McpServerConfig.ExposeNames.PREFIXED;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "prefixed" -> McpServerConfig.ExposeNames.PREFIXED;
            case "raw" -> McpServerConfig.ExposeNames.RAW;
            default -> throw new IllegalArgumentException("Unsupported MCP exposeNames: " + value);
        };
    }
}
