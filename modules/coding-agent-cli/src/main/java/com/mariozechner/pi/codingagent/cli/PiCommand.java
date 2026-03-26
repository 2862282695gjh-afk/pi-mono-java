package com.mariozechner.pi.codingagent.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Main CLI command for Pi Coding Agent.
 * Parses command-line arguments and launches the agent in the requested mode.
 */
@Command(
        name = "pi",
        description = "Pi Coding Agent — an AI-powered software engineering assistant.",
        mixinStandardHelpOptions = true,
        version = "pi 0.1.0"
)
@Component
public class PiCommand implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "AI model to use (e.g. claude-sonnet-4-20250514)")
    String model;

    @Option(names = {"-p", "--prompt"}, description = "Initial prompt to send to the agent")
    String prompt;

    @Option(names = {"--mode"}, description = "Execution mode: interactive, one-shot, or print",
            defaultValue = "interactive")
    String mode;

    @Option(names = {"--cwd"}, description = "Working directory (defaults to current directory)")
    Path cwd;

    @Option(names = {"--system-prompt"}, description = "Additional system prompt text")
    String systemPrompt;

    @Parameters(description = "Prompt arguments (joined with spaces if no -p given)", arity = "0..*")
    List<String> promptArgs;

    @Override
    public Integer call() {
        // Resolve effective prompt: -p flag takes precedence, then positional args
        String effectivePrompt = resolvePrompt();

        // Resolve cwd
        Path effectiveCwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir"));

        // For now, print the resolved configuration. The full agent session wiring
        // (AgentLoop + tools + prompt builder) will be connected in a later task.
        if ("print".equals(mode)) {
            System.out.println("Model: " + model);
            System.out.println("Mode: " + mode);
            System.out.println("CWD: " + effectiveCwd);
            System.out.println("Prompt: " + effectivePrompt);
            return 0;
        }

        if ("one-shot".equals(mode) && effectivePrompt == null) {
            System.err.println("Error: --mode one-shot requires a prompt (-p or positional args)");
            return 1;
        }

        // Placeholder: agent session wiring will be added when AgentSession is implemented
        System.out.println("Pi Coding Agent started (mode=" + mode + ")");
        return 0;
    }

    /**
     * Resolves the effective prompt from the -p flag or positional arguments.
     */
    String resolvePrompt() {
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        if (promptArgs != null && !promptArgs.isEmpty()) {
            return String.join(" ", promptArgs);
        }
        return null;
    }

    // --- Accessors for testing ---

    String getModel() { return model; }
    String getPrompt() { return prompt; }
    String getMode() { return mode; }
    Path getCwd() { return cwd; }
    String getSystemPrompt() { return systemPrompt; }
    List<String> getPromptArgs() { return promptArgs; }
}
