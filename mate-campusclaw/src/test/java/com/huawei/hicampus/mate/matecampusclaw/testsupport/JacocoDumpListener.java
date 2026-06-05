/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.testsupport;

import java.lang.reflect.Method;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flushes JaCoCo execution data before Surefire starts shutting down the forked JVM.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public final class JacocoDumpListener implements LauncherSessionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(JacocoDumpListener.class);

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        try {
            Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
            Method getAgent = rt.getMethod("getAgent");
            Object agent = getAgent.invoke(null);
            Method dump = agent.getClass().getMethod("dump", boolean.class);
            dump.invoke(agent, false);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            LOGGER.debug("JaCoCo agent is not active; skipping coverage dump.", ignored);
        }
    }
}
