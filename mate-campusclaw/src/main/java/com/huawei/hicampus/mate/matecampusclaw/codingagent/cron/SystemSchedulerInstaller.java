/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.cron;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.mate.matecampusclaw.ai.utils.CampusClawHome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在 macOS 或 Linux 系统调度器中安装和卸载 CampusClaw 定时任务。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/20]
 * @since [br_eCampusCore 26.0.0]
 */
public class SystemSchedulerInstaller {

    private static final Logger log = LoggerFactory.getLogger(SystemSchedulerInstaller.class);

    private static final String LABEL = "com.huawei.hicampus.mate.matecampusclaw.cron";
    private static final String LAUNCHER_SCRIPT_NAME = "campusclaw.sh";
    private static final Path DEFAULT_PLIST_PATH = Path.of(System.getProperty("user.home"))
            .resolve("Library/LaunchAgents")
            .resolve(LABEL + ".plist");
    private static final String CRONTAB_MARKER = "# campusclaw-cron";
    private static final long PROC_TIMEOUT_SECONDS = 10L;
    private static final long ID_TIMEOUT_SECONDS = 5L;

    /**
     * 当前实例使用的 launchd plist 路径。生产环境使用用户目录，测试可注入临时路径。
     */
    private final Path plistPath;

    /**
     * 测试使用的可选操作系统覆盖值；{@code null} 表示读取 JVM 的 {@code os.name}。
     */
    private final Os osOverride;

    private final Path launcherScript;

    /** 测试固定平台分支时使用的操作系统枚举。 */
    enum Os {
        MAC,
        LINUX,
        UNSUPPORTED
    }

    public SystemSchedulerInstaller(Path launcherScript) {
        this(launcherScript, DEFAULT_PLIST_PATH, null);
    }

    /**
     * 将 launchd plist 重定向到测试路径，并可固定操作系统分支。
     *
     * @param launcherScript 启动脚本路径
     * @param plistPath launchd plist 路径
     * @param osOverride 指定的操作系统，{@code null} 表示使用当前 JVM 所在系统
     */
    SystemSchedulerInstaller(Path launcherScript, Path plistPath, Os osOverride) {
        this.launcherScript = launcherScript.toAbsolutePath().normalize();
        this.plistPath = plistPath;
        this.osOverride = osOverride;
    }

    /**
     * 为当前操作系统安装定时触发器。
     * @param intervalSeconds 触发间隔秒数，默认值为 60
     * @return 可读的状态信息
     *
     * @throws IOException 操作失败时抛出
     */
    public String install(int intervalSeconds) throws IOException {
        return switch (currentOs()) {
            case MAC -> installLaunchd(intervalSeconds);
            case LINUX -> installCrontab(intervalSeconds);
            case UNSUPPORTED -> throw unsupportedOs();
        };
    }

    /**
     * 卸载当前操作系统的定时触发器。
     *
     * @return 操作结果
     *
     * @throws IOException 操作失败时抛出
     */
    public String uninstall() throws IOException {
        return switch (currentOs()) {
            case MAC -> uninstallLaunchd();
            case LINUX -> uninstallCrontab();
            case UNSUPPORTED -> throw unsupportedOs();
        };
    }

    /**
     * 检查当前操作系统是否已经安装定时触发器。
     *
     * @return 检查结果
     */
    public String status() {
        return switch (currentOs()) {
            case MAC -> statusLaunchd();
            case LINUX -> statusCrontab();
            case UNSUPPORTED -> throw unsupportedOs();
        };
    }

    // macOS launchd

    /**
     * launchd plist 模板，依次填充 label、launcher、interval、logDir 和 logDir。
     */
    private static final String PLIST_TEMPLATE =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
            "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>%s</string>
                <key>ProgramArguments</key>
                <array>
                    <string>%s</string>
                    <string>--cron-tick</string>
                </array>
                <key>StartInterval</key>
                <integer>%d</integer>
                <key>StandardOutPath</key>
                <string>%s/cron-tick.log</string>
                <key>StandardErrorPath</key>
                <string>%s/cron-tick.err</string>
                <key>RunAtLoad</key>
                <true/>
            </dict>
            </plist>
            """;

    private String installLaunchd(int intervalSeconds) throws IOException {
        Path logDir = CampusClawHome.agentDir().resolve("cron");
        Files.createDirectories(logDir);
        Files.createDirectories(plistPath.getParent());
        String plist = PLIST_TEMPLATE.formatted(LABEL, launcherScript, intervalSeconds, logDir, logDir);
        Files.writeString(plistPath, plist);
        reloadLaunchd();
        return "Installed launchd agent: " + plistPath
                + "\nInterval: every " + intervalSeconds + "s"
                + "\nLogs: " + logDir + "/cron-tick.log";
    }

    // 停止已有 plist 并重新加载；bootstrap 不可用时回退到旧版 load 命令。
    private void reloadLaunchd() {
        runDiscardingOutput("launchctl bootout", "launchctl", "bootout", "gui/" + getUid(), plistPath.toString());
        Integer exit = runDiscardingOutput(
                "launchctl bootstrap", "launchctl", "bootstrap", "gui/" + getUid(), plistPath.toString());
        if (exit == null || exit != 0) {
            runDiscardingOutput("launchctl load", "launchctl", "load", plistPath.toString());
        }
    }

    /**
     * 执行命令并在操作系统层丢弃标准输出和错误输出，避免管道阻塞。
     *
     * @param description 调试日志使用的操作描述
     * @param command 要执行的参数数组
     * @return 进程退出码；无法启动、被中断或超时时返回 {@code null}
     */
    private Integer runDiscardingOutput(String description, String... command) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            drainToNull(proc.getInputStream());
            drainToNull(proc.getErrorStream());

            if (!proc.waitFor(PROC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.debug("{} timed out after {}s", description, PROC_TIMEOUT_SECONDS);
                return null;
            }
            return proc.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            log.debug("{} interrupted", description, e);
            return null;
        } catch (IOException e) {
            log.debug("{} failed", description, e);
            return null;
        }
    }

    private static void drainToNull(InputStream in) throws IOException {
        try (in;
                OutputStream sink = OutputStream.nullOutputStream()) {
            in.transferTo(sink);
        }
    }

    private String uninstallLaunchd() throws IOException {
        if (!Files.exists(plistPath)) {
            return "Not installed (no plist found)";
        }
        Integer exit = runDiscardingOutput(
                "launchctl bootout", "launchctl", "bootout", "gui/" + getUid(), plistPath.toString());
        if (exit == null) {
            // 回退到旧版 unload 命令。
            runDiscardingOutput("launchctl unload", "launchctl", "unload", plistPath.toString());
        }
        Files.deleteIfExists(plistPath);
        return "Uninstalled launchd agent: " + LABEL;
    }

    private String statusLaunchd() {
        if (!Files.exists(plistPath)) {
            return "Not installed";
        }
        Process proc = null;
        try {
            proc = new ProcessBuilder("launchctl", "print", "gui/" + getUid() + "/" + LABEL)
                    .redirectErrorStream(true)
                    .start();

            // 在 waitFor 前读取标准输出，避免管道填满导致死锁。
            proc.getInputStream().readAllBytes();
            if (!proc.waitFor(PROC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "Plist exists: " + plistPath + " (status check timed out)";
            }
            if (proc.exitValue() == 0) {
                return "Installed and active\nPlist: " + plistPath;
            } else {
                return "Plist exists but service not loaded\nPlist: " + plistPath + "\nRun: /cron install to reload";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return "Plist exists: " + plistPath + " (interrupted)";
        } catch (IOException e) {
            log.debug("launchctl print failed", e);
            return "Plist exists: " + plistPath + " (status check failed)";
        }
    }

    // Linux crontab

    private String installCrontab(int intervalSeconds) throws IOException {
        int minutes = Math.max(1, intervalSeconds / 60);
        String cronExpr = minutes >= 60 ? "0 */" + (minutes / 60) + " * * *" : "*/" + minutes + " * * * *";
        String cronLine = cronExpr + " " + launcherScript + " --cron-tick >> "
                + CampusClawHome.agentDir().resolve("cron/cron-tick.log")
                + " 2>&1 " + CRONTAB_MARKER;

        String existing = getCurrentCrontab();

        // 删除已有的 CampusClaw 条目。
        String cleaned = existing.lines()
                .filter(l -> !l.contains(CRONTAB_MARKER))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        String newCrontab = cleaned.isEmpty() ? cronLine + "\n" : cleaned + "\n" + cronLine + "\n";

        writeCrontab(newCrontab);
        return "Installed crontab entry: " + cronLine;
    }

    private String uninstallCrontab() throws IOException {
        String existing = getCurrentCrontab();
        String cleaned = existing.lines()
                .filter(l -> !l.contains(CRONTAB_MARKER))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        if (cleaned.equals(existing)) {
            return "Not installed (no crontab entry found)";
        }
        writeCrontab(cleaned.isEmpty() ? "" : cleaned + "\n");
        return "Removed crontab entry";
    }

    private String statusCrontab() {
        try {
            String existing = getCurrentCrontab();
            var entry = existing.lines().filter(l -> l.contains(CRONTAB_MARKER)).findFirst();
            return entry.map(s -> "Installed: " + s).orElse("Not installed");
        } catch (Exception e) {
            return "Unable to check crontab: " + e.getMessage();
        }
    }

    private String getCurrentCrontab() throws IOException {
        Process proc = null;
        try {
            proc = new ProcessBuilder("crontab", "-l").redirectErrorStream(true).start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(PROC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.debug("crontab -l timed out after {}s", PROC_TIMEOUT_SECONDS);
                return "";
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return "";
        }
    }

    private void writeCrontab(String content) throws IOException {
        var tmpFile = Files.createTempFile("campusclaw-crontab-", ".tmp");
        Files.writeString(tmpFile, content);
        runDiscardingOutput("crontab apply", "crontab", tmpFile.toString());
        Files.deleteIfExists(tmpFile);
    }

    // 平台辅助方法

    private Os currentOs() {
        if (osOverride != null) {
            return osOverride;
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return Os.MAC;
        }
        if (osName.contains("linux")) {
            return Os.LINUX;
        }
        return Os.UNSUPPORTED;
    }

    private static UnsupportedOperationException unsupportedOs() {
        String osName = System.getProperty("os.name", "unknown");
        return new UnsupportedOperationException("CampusClaw supports macOS and Linux only: " + osName);
    }

    private static String getUid() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("id", "-u")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String uid = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!proc.waitFor(ID_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.debug("id -u timed out after {}s; using fallback uid", ID_TIMEOUT_SECONDS);
                return "501";
            }
            return uid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return "501";
        } catch (IOException e) {
            log.debug("id -u failed; using fallback uid", e);
            return "501";
        }
    }

    /**
     * 从运行中 JAR 的位置自动检测 macOS/Linux 启动脚本。
     *
     * @return 启动脚本路径，未找到时返回 {@code null}
     */
    public static Path detectLauncherScript() {
        try {
            Path jarPath = Path.of(SystemSchedulerInstaller.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());

            // JAR 位于 modules/coding-agent-cli/build/libs/*.jar，启动脚本位于仓库根目录。
            Path root = jarPath.getParent().getParent().getParent().getParent().getParent();
            Path script = root.resolve(LAUNCHER_SCRIPT_NAME);
            if (Files.exists(script)) {
                return script;
            }
        } catch (Exception e) {
            // JAR 相对路径检测采用尽力策略，失败时回退到当前工作目录。
            log.debug("jar-relative launcher lookup failed; falling back to cwd-relative", e);
        }

        // 回退到当前工作目录。
        Path cwd = Path.of(System.getProperty("user.dir")).resolve(LAUNCHER_SCRIPT_NAME);
        if (Files.exists(cwd)) {
            return cwd;
        }
        return null;
    }
}
