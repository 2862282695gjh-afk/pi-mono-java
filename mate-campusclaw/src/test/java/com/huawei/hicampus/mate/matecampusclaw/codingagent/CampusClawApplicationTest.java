/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.cli.CampusClawCommand;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Unit tests for {@link CampusClawApplication}. We do NOT boot Spring; the class is a thin
 * shell around picocli, so we mock {@link CampusClawCommand} and a tiny picocli
 * {@link IFactory} that defers to {@link CommandLine#defaultFactory()}. The {@code main} entry
 * point cannot be tested directly without spinning up the whole Spring context — the side
 * effects we DO want to verify (system properties) are exercised via {@link Main} below.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/09]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class CampusClawApplicationTest {

    private static IFactory defaultFactory() {
        IFactory delegate = CommandLine.defaultFactory();
        return new IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                return delegate.create(cls);
            }
        };
    }

    @Nested
    class Construction {

        @Test
        void newInstanceExitCodeDefaultsToZero() {
            CampusClawCommand cmd = Mockito.mock(CampusClawCommand.class);
            CampusClawApplication app = new CampusClawApplication(cmd, defaultFactory());

            assertThat(app.getExitCode()).isZero();
        }

        @Test
        void constructorAcceptsArgumentsWithoutSideEffects() {
            CampusClawCommand cmd = Mockito.mock(CampusClawCommand.class);
            assertThatNoException().isThrownBy(() -> new CampusClawApplication(cmd, defaultFactory()));
        }
    }

    @Nested
    class Run {

        @Test
        void runPropagatesPicocliExitCodeForUnknownFlag() {
            // Picocli returns USAGE (exit 2) for unrecognised options. The mock CampusClawCommand
            // is decorated with @Command (inherited annotation), so picocli can build its model.
            CampusClawCommand cmd = Mockito.mock(CampusClawCommand.class);
            CampusClawApplication app = new CampusClawApplication(cmd, defaultFactory());

            app.run("--this-flag-does-not-exist");

            // Picocli returns 2 for invalid input.
            assertThat(app.getExitCode()).isEqualTo(2);
        }

        @Test
        void runWithHelpExitsWithZero() {
            CampusClawCommand cmd = Mockito.mock(CampusClawCommand.class);
            CampusClawApplication app = new CampusClawApplication(cmd, defaultFactory());

            app.run("--help");

            // mixinStandardHelpOptions = true on CampusClawCommand → --help returns 0.
            assertThat(app.getExitCode()).isZero();
        }

        @Test
        void runWithVersionExitsWithZero() {
            CampusClawCommand cmd = Mockito.mock(CampusClawCommand.class);
            CampusClawApplication app = new CampusClawApplication(cmd, defaultFactory());

            app.run("--version");

            assertThat(app.getExitCode()).isZero();
        }
    }
}
