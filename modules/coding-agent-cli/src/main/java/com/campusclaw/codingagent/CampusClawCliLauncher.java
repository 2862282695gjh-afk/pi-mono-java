/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent;

import java.util.Arrays;

import com.campusclaw.codingagent.cli.CampusClawCommand;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * CampusClaw CLI 进程启动器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
final class CampusClawCliLauncher {
    private static final String CLI_COMMAND = "cli";

    private CampusClawCliLauncher() {}

    static boolean isCliInvocation(String[] args) {
        return args.length > 0 && CLI_COMMAND.equals(args[0]);
    }

    static void launch(String[] args) {
        configureTerminal();
        String[] cliArgs = Arrays.copyOfRange(args, 1, args.length);
        int exitCode = execute(cliArgs);
        System.exit(exitCode);
    }

    private static int execute(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CampusClawCliConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("campusclaw-cli")
                .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=true")
                .run(args)) {
            CampusClawCommand command = context.getBean(CampusClawCommand.class);
            IFactory factory = context.getBean(IFactory.class);
            return new CommandLine(command, factory).execute(args);
        }
    }

    private static void configureTerminal() {
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");
        System.setProperty("org.jline.terminal.jansi", "false");
    }
}
