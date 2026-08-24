/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin.CompactCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin.ModelCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin.NameCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin.ThinkingCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionResult;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * Slash Command 核心与四个保留处理器的宿主无关契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class SlashCommandRegistryTest {
    @Test
    void executesFourRetainedCommandsThroughTestPorts() {
        SlashCommandRegistry registry = registry();
        TestSession session = new TestSession();
        List<String> output = new ArrayList<>();
        SlashCommandContext context = new SlashCommandContext(session, output::add);

        assertThat(registry.execute("/model model-b", context)).isTrue();
        assertThat(registry.execute("/thinking on", context)).isTrue();
        assertThat(registry.execute("/compact preserve decisions", context)).isTrue();
        assertThat(registry.execute("/name Review chat", context)).isTrue();

        assertThat(session.modelId).isEqualTo("model-b");
        assertThat(session.thinking).isTrue();
        assertThat(session.compactionInstructions).isEqualTo("preserve decisions");
        assertThat(session.displayName).contains("Review chat");
        assertThat(output).hasSize(4);
    }

    @Test
    void remainsUnmanagedAndDoesNotConsumeUnknownInput() {
        assertThat(SlashCommandRegistry.class.isAnnotationPresent(Component.class))
                .isFalse();
        assertThat(registry().execute("/unknown", new SlashCommandContext(new TestSession(), ignored -> {})))
                .isFalse();
        assertThat(registry().execute("ordinary message", new SlashCommandContext(new TestSession(), ignored -> {})))
                .isFalse();
    }

    private static SlashCommandRegistry registry() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new ModelCommand());
        registry.register(new ThinkingCommand());
        registry.register(new CompactCommand());
        registry.register(new NameCommand());
        return registry;
    }

    private static final class TestSession implements SlashCommandSession {
        private String modelId = "model-a";

        private boolean thinking;

        private String compactionInstructions;

        private Optional<String> displayName = Optional.empty();

        @Override
        public String currentModelId() {
            return modelId;
        }

        @Override
        public void changeModel(String modelId) {
            this.modelId = modelId;
        }

        @Override
        public boolean thinkingEnabled() {
            return thinking;
        }

        @Override
        public void changeThinking(boolean enabled) {
            thinking = enabled;
        }

        @Override
        public SessionCompactionResult compact(String customInstructions) {
            compactionInstructions = customInstructions;
            return new SessionCompactionResult("summary", List.of(), 1, 100, 20, Usage.empty());
        }

        @Override
        public Optional<String> displayName() {
            return displayName;
        }

        @Override
        public void changeDisplayName(String name) {
            displayName = Optional.of(name);
        }
    }
}
