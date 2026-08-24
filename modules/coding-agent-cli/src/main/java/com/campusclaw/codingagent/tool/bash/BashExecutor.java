/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.bash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供与 Bash 工具解耦的命令执行能力。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class BashExecutor {

    private static final Logger log = LoggerFactory.getLogger(BashExecutor.class);

    /**
     * 执行 Bash 命令并分别捕获标准输出和错误输出。
     *
     * @param command Shell 命令
     * @param cwd 工作目录
     * @param options 执行选项
     * @return 执行结果
     * @throws IOException 进程无法启动时抛出
     */
    public BashExecutionResult execute(String command, Path cwd, BashExecutorOptions options) throws IOException {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ShellResolver.ShellConfig shell = ShellResolver.resolve();
        String nullDevice = windows ? "NUL" : "/dev/null";
        List<String> argv = new ArrayList<>(shell.args().size() + 2);
        argv.add(shell.shell());
        argv.addAll(shell.args());
        argv.add(command);
        Process process = startProcess(argv, cwd, nullDevice, options);
        var stdoutBuf = new ByteArrayOutputStream();
        var stderrBuf = new ByteArrayOutputStream();
        Thread stdoutDrainer =
                Thread.ofVirtual().name("bash-stdout-drainer").start(() -> drain(process.getInputStream(), stdoutBuf));
        Thread stderrDrainer =
                Thread.ofVirtual().name("bash-stderr-drainer").start(() -> drain(process.getErrorStream(), stderrBuf));
        try {
            boolean timedOut = waitForProcess(process, options);
            joinDrainers(stdoutDrainer, stderrDrainer);
            Integer exitCode = timedOut ? null : process.exitValue();
            return result(exitCode, stdoutBuf, stderrBuf);
        } catch (InterruptedException e) {
            killProcessTree(process);
            Thread.currentThread().interrupt();
            joinDrainers(stdoutDrainer, stderrDrainer);
            return result(null, stdoutBuf, stderrBuf);
        }
    }

    private static Process startProcess(
            List<String> arguments, Path cwd, String nullDevice, BashExecutorOptions options) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.directory(cwd.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice)));
        if (!options.env().isEmpty()) {
            builder.environment().putAll(options.env());
        }
        Process process = builder.start();
        if (options.signal() != null) {
            options.signal().onCancel(() -> killProcessTree(process));
        }
        return process;
    }

    private static boolean waitForProcess(Process process, BashExecutorOptions options) throws InterruptedException {
        if (options.timeout() == null) {
            process.waitFor();
            return false;
        }
        boolean finished = process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (finished) {
            return false;
        }
        killProcessTree(process);
        process.waitFor(5, TimeUnit.SECONDS);
        return true;
    }

    private static BashExecutionResult result(
            Integer exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        return new BashExecutionResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static void drain(InputStream is, ByteArrayOutputStream out) {
        try (is) {
            byte[] buffer = new byte[4096];
            int bytesRead = is.read(buffer);
            while (bytesRead != -1) {
                out.write(buffer, 0, bytesRead);
                bytesRead = is.read(buffer);
            }
        } catch (IOException e) {
            // 超时或取消销毁进程时流会关闭，这是预期路径。
            log.debug("Bash output drain stopped because the process stream was closed", e);
        }
    }

    /**
     * 销毁进程及其仍存活的全部后代进程。
     *
     * @param process 待销毁进程
     */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinDrainers(Thread stdout, Thread stderr) {
        try {
            stdout.join(5000);
            stderr.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
