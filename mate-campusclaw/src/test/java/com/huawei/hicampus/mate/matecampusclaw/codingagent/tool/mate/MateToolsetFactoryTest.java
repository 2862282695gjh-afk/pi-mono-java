/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link MateToolsetFactory} 的 Session 状态隔离测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolsetFactoryTest {

    @Test
    void shouldCreateNewToolsAndIndependentStateForEverySession() {
        MateToolsetFactory factory = new MateToolsetFactory(new MockMateToolClient(), null);
        MateToolSessionState first = factory.createSession("agent-1", Map.of());
        MateToolSessionState second = factory.createSession("agent-2", Map.of());

        assertThat(first).isNotSameAs(second);
        assertThat(first.createListTool()).isNotSameAs(first.createListTool());
        assertThat(first.createCallTool()).isNotSameAs(first.createCallTool());
        assertThat(first.createListTool()).hasFieldOrPropertyWithValue("discovery", extractDiscovery(first));
        assertThat(second.createListTool()).hasFieldOrPropertyWithValue("discovery", extractDiscovery(second));
        assertThat(extractDiscovery(first)).isNotSameAs(extractDiscovery(second));
    }

    private static MateToolDiscovery extractDiscovery(MateToolSessionState state) {
        try {
            var field = MateToolSessionState.class.getDeclaredField("discovery");
            field.setAccessible(true);
            return (MateToolDiscovery) field.get(state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
