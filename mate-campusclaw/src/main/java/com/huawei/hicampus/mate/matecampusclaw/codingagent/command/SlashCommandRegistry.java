/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按名称保存和分发 Slash Command 的非托管注册表。
 *
 * <p>该类型不注册为 Spring Bean，任何 Runtime Host 都不会自动启用命令解析。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class SlashCommandRegistry {
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public void register(SlashCommand command) {
        SlashCommand previous = commands.putIfAbsent(command.name(), command);
        if (previous != null) {
            throw new IllegalArgumentException("Slash Command name is duplicated: " + command.name());
        }
    }

    public Optional<SlashCommand> get(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public Collection<SlashCommand> getAll() {
        return Collections.unmodifiableCollection(commands.values());
    }

    public boolean execute(String input, SlashCommandContext context) {
        ParsedSlashCommand parsed = parse(input);
        if (parsed == null) {
            return false;
        }
        SlashCommand command = commands.get(parsed.name());
        if (command == null) {
            return false;
        }
        command.execute(context, parsed.arguments());
        return true;
    }

    private static ParsedSlashCommand parse(String input) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        String stripped = input.substring(1).trim();
        if (stripped.isEmpty()) {
            return null;
        }
        int separator = stripped.indexOf(' ');
        String name = separator < 0 ? stripped : stripped.substring(0, separator);
        String arguments =
                separator < 0 ? "" : stripped.substring(separator + 1).trim();
        return new ParsedSlashCommand(name, arguments);
    }

    private record ParsedSlashCommand(String name, String arguments) {}
}
