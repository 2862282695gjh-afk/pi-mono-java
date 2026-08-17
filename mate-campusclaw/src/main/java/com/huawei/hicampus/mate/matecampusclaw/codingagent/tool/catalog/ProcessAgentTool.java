/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentTool} adapter for a declarative external process tool.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class ProcessAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ProcessAgentTool.class);

    private final ToolDeclaration declaration;
    private final Path cwd;

    public ProcessAgentTool(ToolDeclaration declaration, Path cwd) {
        this.declaration = declaration;
        this.cwd = cwd;
    }

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public String label() {
        return declaration.label();
    }

    @Override
    public String description() {
        return declaration.description();
    }

    @Override
    public JsonNode parameters() {
        return declaration.inputSchema();
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        var process = startProcess();
        if (signal != null) {
            signal.onCancel(() -> killProcessTree(process));
        }
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        Thread stdoutDrainer = Thread.ofVirtual()
                .name("process-tool-stdout-" + name())
                .start(() -> drain(process.getInputStream(), stdout));
        Thread stderrDrainer = Thread.ofVirtual()
                .name("process-tool-stderr-" + name())
                .start(() -> drain(process.getErrorStream(), stderr));

        boolean finished = process.waitFor(declaration.execution().timeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            killProcessTree(process);
            joinDrainers(stdoutDrainer, stderrDrainer);
            throw new IllegalStateException("Declarative tool '" + name() + "' timed out after "
                    + declaration.execution().timeoutSeconds() + " seconds");
        }
        joinDrainers(stdoutDrainer, stderrDrainer);
        int exitCode = process.exitValue();
        String stdoutText = stdout.toString(StandardCharsets.UTF_8);
        String stderrText = stderr.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Declarative tool '" + name() + "' exited with code " + exitCode + ": " + stderrText);
        }
        return new AgentToolResult(
                List.of(new TextContent(stdoutText)),
                Map.of("exitCode", exitCode, "stdout", stdoutText, "stderr", stderrText));
    }

    private Process startProcess() throws IOException {
        var processBuilder = new ProcessBuilder(declaration.execution().command());
        processBuilder.directory(cwd.toFile());
        processBuilder.environment().putAll(declaration.execution().env());
        return processBuilder.start();
    }

    private static void drain(InputStream inputStream, ByteArrayOutputStream outputStream) {
        try (inputStream) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            log.debug("process tool output drain stopped", e);
        }
    }

    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinDrainers(Thread stdout, Thread stderr) {
        try {
            stdout.join(5000L);
            stderr.join(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
