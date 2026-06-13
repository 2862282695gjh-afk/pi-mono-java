/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import com.campusclaw.codingagent.config.ToolExecutionProperties;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DockerSandboxClient}. Without a real Docker daemon
 * we exercise the disabled / unavailable paths — those are the relevant
 * failure modes when the host doesn't have Docker installed and the
 * fall-back semantics matter for security correctness.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/09]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class DockerSandboxClientTest {

    private static DockerSandboxClient client(boolean sandboxEnabled) {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.setSandboxExecutionEnabled(sandboxEnabled);
        return new DockerSandboxClient(props, new SandboxSecurityPolicy());
    }

    private static DockerSandboxClient enabledClient(boolean ephemeral) {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.setSandboxExecutionEnabled(true);
        props.setUseEphemeralContainers(ephemeral);

        // Point at a deliberately bogus docker socket so even hosts with Docker installed
        // hit the "daemon unreachable" code path rather than spinning up real containers.
        props.setDockerHost("unix:///nonexistent/campusclaw-sandbox-test-docker.sock");
        props.setSandboxWorkerImage("alpine:3.19");
        props.setSandboxWorkerMemory("256m");
        props.setSandboxWorkerCpu(0.5);
        return new DockerSandboxClient(props, new SandboxSecurityPolicy());
    }

    @Nested
    class SandboxDisabled {

        @Test
        void initializeSkipsWhenDisabled() {
            DockerSandboxClient c = client(false);
            assertThat(c.isAvailable()).isFalse();
            assertThat(c.getWorkerContainerId()).isNull();
        }

        @Test
        void executeReturnsErrorWhenUnavailable() {
            DockerSandboxClient c = client(false);
            SandboxResult result = c.execute(List.of("echo", "hi"), ResourceLimits.defaults());
            assertThat(result.getErrorMessage()).contains("not available");
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void shutdownIsSafeWhenInactive() {
            DockerSandboxClient c = client(false);

            // No worker container ever spawned — shutdown should be a no-op rather than throwing.
            assertThatNoException().isThrownBy(c::shutdown);
        }

        @Test
        void executeWithDifferentLimitsStillFailsCleanly() {
            DockerSandboxClient c = client(false);
            ResourceLimits limits = ResourceLimits.builder()
                    .timeoutSeconds(5)
                    .memoryMb(128)
                    .cpuQuota(0.5)
                    .build();
            SandboxResult result = c.execute(List.of("ls"), limits);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Nested
    class SandboxEnabledButDockerUnreachable {

        @Test
        void ephemeralModeReportsUnavailableWhenDaemonDown() {
            // sandbox enabled + ephemeral mode + bogus socket → `docker version` fails →
            // dockerAvailable stays false; execute() should report unavailable.
            DockerSandboxClient c = enabledClient(true);

            assertThat(c.isAvailable()).isFalse();
            assertThat(c.getWorkerContainerId()).isNull();

            SandboxResult result = c.execute(List.of("echo", "hi"), ResourceLimits.defaults());
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("not available");
        }

        @Test
        void persistentModeReportsUnavailableWhenDaemonDown() {
            // sandbox enabled + persistent worker mode + bogus socket → version probe fails →
            // worker never started; execute() reports unavailable.
            DockerSandboxClient c = enabledClient(false);

            assertThat(c.isAvailable()).isFalse();
            assertThat(c.getWorkerContainerId()).isNull();

            SandboxResult result = c.execute(List.of("uname", "-a"), ResourceLimits.defaults());
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        void shutdownIsSafeWhenInitFailed() {
            DockerSandboxClient c = enabledClient(false);

            // workerContainerId never assigned → shutdown is a no-op.
            assertThatNoException().isThrownBy(c::shutdown);
        }

        @Test
        void multipleExecutesStayInUnavailableState() {
            DockerSandboxClient c = enabledClient(true);
            for (int i = 0; i < 3; i++) {
                SandboxResult r = c.execute(List.of("echo", String.valueOf(i)), ResourceLimits.defaults());
                assertThat(r.isSuccess()).isFalse();
            }
            assertThat(c.isAvailable()).isFalse();
        }
    }
}
