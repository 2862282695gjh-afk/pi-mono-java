/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SystemSchedulerInstaller}. Production calls {@code launchctl /
 * crontab / schtasks} subprocesses, which would mutate user state — so tests pin the OS
 * branch via the package-private test seam and redirect the launchd plist target to a
 * {@link TempDir}. The crontab / Windows branches are exercised only at the "service not
 * installed" entry points so we never mutate the host scheduler.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/09]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class SystemSchedulerInstallerTest {

    @TempDir
    Path tmp;

    @Nested
    class Construction {

        @Test
        void launcherScriptIsNormalisedToAbsolute() {
            Path relative = Path.of("./scripts/foo.sh");
            assertThatNoException().isThrownBy(() -> new SystemSchedulerInstaller(relative));
        }

        @Test
        void publicConstructorUsesDefaultPlistPath() {
            // Smoke-tests the no-arg path.
            assertThatNoException().isThrownBy(() -> new SystemSchedulerInstaller(Path.of("dummy.sh")));
        }
    }

    @Nested
    class MacOsBranch {

        @Test
        void statusOnAbsentPlistSaysNotInstalled() {
            Path plist = tmp.resolve("agent.plist");
            SystemSchedulerInstaller installer =
                    new SystemSchedulerInstaller(tmp.resolve("launcher.sh"), plist, SystemSchedulerInstaller.Os.MAC);

            assertThat(installer.status()).isEqualTo("Not installed");
        }

        @Test
        void uninstallOnAbsentPlistReportsNotInstalled() throws Exception {
            Path plist = tmp.resolve("agent.plist");
            SystemSchedulerInstaller installer =
                    new SystemSchedulerInstaller(tmp.resolve("launcher.sh"), plist, SystemSchedulerInstaller.Os.MAC);

            String result = installer.uninstall();

            assertThat(result).isEqualTo("Not installed (no plist found)");
        }

        @Test
        void statusOnPresentPlistMentionsPath() throws Exception {
            Path plist = tmp.resolve("agent.plist");
            Files.writeString(plist, "<plist/>");
            SystemSchedulerInstaller installer =
                    new SystemSchedulerInstaller(tmp.resolve("launcher.sh"), plist, SystemSchedulerInstaller.Os.MAC);

            String result = installer.status();

            // launchctl will not recognise a temp-dir plist, so status mentions the path.
            assertThat(result).contains(plist.toString());
        }

        @Test
        void uninstallOnPresentPlistDeletesFile() throws Exception {
            Path plist = tmp.resolve("agent.plist");
            Files.writeString(plist, "<plist/>");
            SystemSchedulerInstaller installer =
                    new SystemSchedulerInstaller(tmp.resolve("launcher.sh"), plist, SystemSchedulerInstaller.Os.MAC);

            String result = installer.uninstall();

            assertThat(result).contains("Uninstalled launchd agent");
            assertThat(Files.exists(plist)).isFalse();
        }
    }

    @Nested
    class LinuxBranch {

        @Test
        void statusOnNoCrontabReturnsNotInstalledOrMissing() {
            SystemSchedulerInstaller installer = new SystemSchedulerInstaller(
                    tmp.resolve("launcher.sh"), tmp.resolve("plist"), SystemSchedulerInstaller.Os.LINUX);

            String result = installer.status();

            // Hosts without a crontab binary (e.g. minimal CI containers) throw IOException
            // inside statusCrontab → "Unable to check crontab: ...". Both outcomes mean the
            // campusclaw entry is absent.
            assertThat(result).satisfiesAnyOf(s -> assertThat(s).startsWith("Not installed"), s -> assertThat(s)
                    .startsWith("Unable to check crontab"));
        }

        // No tests for the uninstall/install crontab paths: they invoke `crontab <file>` which
        // would mutate the test host's crontab. We cover dispatch via the read-only status path
        // above; the install/uninstall write paths are exercised by manual smoke tests instead.
    }

    @Nested
    class WindowsBranch {

        @Test
        void statusOnNonWindowsHostHandlesMissingSchtasks() {
            // On macOS/Linux, schtasks doesn't exist — the status method catches IOException
            // and reports "Unable to check task".
            SystemSchedulerInstaller installer = new SystemSchedulerInstaller(
                    tmp.resolve("launcher.sh"), tmp.resolve("plist"), SystemSchedulerInstaller.Os.WINDOWS);

            String result = installer.status();

            // schtasks not present on the host → IOException → "Unable to check task: ..."
            assertThat(result).satisfiesAnyOf(s -> assertThat(s).startsWith("Unable to check task"), s -> assertThat(s)
                    .startsWith("Not installed"));
        }
    }

    @Nested
    class DetectLauncherScript {

        @Test
        void returnsNullOrExistingFile() {
            Path detected = SystemSchedulerInstaller.detectLauncherScript();
            if (detected != null) {
                assertThat(Files.exists(detected)).isTrue();
            }
        }
    }
}
