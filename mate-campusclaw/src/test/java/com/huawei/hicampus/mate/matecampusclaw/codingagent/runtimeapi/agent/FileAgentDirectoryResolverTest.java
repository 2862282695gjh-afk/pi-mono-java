/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent 当前只读目录和模型白名单解析测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
class FileAgentDirectoryResolverTest {
    private static final String AGENT_ID = "agent_011CZkYqphY8vELVzwCUpqiQ";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void resolvesDirectCurrentDirectoryWithoutRevisionFiles() throws Exception {
        Path agent = createAgent("{\"defaultModel\":\"model-a\",\"enabledModels\":[\"model-a\",\"model-b\"]}");

        AgentDirectorySnapshotDTO snapshot = resolver().resolve(AGENT_ID);

        assertThat(snapshot.runtimeDirectory())
                .isEqualTo(agent.resolve(".campusclaw").toRealPath());
        assertThat(snapshot.defaultModelId()).isEqualTo("model-a");
        assertThat(snapshot.enabledModelIds()).containsExactly("model-a", "model-b");
        assertThat(agent.resolve("current.json")).doesNotExist();
    }

    @Test
    void defaultsRuntimeRootToAgentDirectory() {
        assertThat(new RuntimeAgentDirectoryProperties().getRoot()).isEqualTo(Path.of("agent"));
    }

    @Test
    void rejectsDuplicateOrMissingDefaultModel() throws Exception {
        createAgent("{\"defaultModel\":\"model-a\",\"enabledModels\":[\"model-b\",\"model-b\"]}");

        assertThatThrownBy(() -> resolver().resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
    }

    @Test
    void rejectsSettingsSymlinkThatEscapesAgentDirectory() throws Exception {
        Path agent = temporaryDirectory.resolve(AGENT_ID);
        Path managed = Files.createDirectories(agent.resolve(".campusclaw"));
        Path outside = temporaryDirectory.resolve("outside.json");
        Files.writeString(
                outside, "{\"defaultModel\":\"model-a\",\"enabledModels\":[\"model-a\"]}", StandardCharsets.UTF_8);
        Files.createSymbolicLink(managed.resolve("settings.json"), outside);

        assertThatThrownBy(() -> resolver().resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
    }

    @Test
    void rejectsLegacyCampusAgentDirectory() throws Exception {
        Path agent = temporaryDirectory.resolve(AGENT_ID);
        Path legacyDirectory = Files.createDirectories(agent.resolve(".campusagent"));
        Files.writeString(
                legacyDirectory.resolve("settings.json"),
                "{\"defaultModel\":\"model-a\",\"enabledModels\":[\"model-a\"]}",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> resolver().resolve(AGENT_ID))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
    }

    private Path createAgent(String settings) throws Exception {
        Path agent = temporaryDirectory.resolve(AGENT_ID);
        Path managed = Files.createDirectories(agent.resolve(".campusclaw"));
        Files.writeString(managed.resolve("settings.json"), settings, StandardCharsets.UTF_8);
        return agent;
    }

    private FileAgentDirectoryResolver resolver() {
        RuntimeAgentDirectoryProperties properties = new RuntimeAgentDirectoryProperties();
        properties.setRoot(temporaryDirectory);
        return new FileAgentDirectoryResolver(properties, new ObjectMapper());
    }
}
