/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolProvider;
import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.provider.ApiProvider;
import com.campusclaw.ai.provider.ApiProviderRegistry;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.StreamOptions;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.cron.model.CronJob;
import com.campusclaw.cron.model.CronJobState;
import com.campusclaw.cron.model.CronPayload;
import com.campusclaw.cron.model.CronRunRecord;
import com.campusclaw.cron.model.CronSchedule;
import com.campusclaw.cron.store.CronRunLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.annotation.Nullable;

class CronJobExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void executeResolvesAllowedToolsThroughConfiguredToolProvider() {
        var selectedTool = new TestTool("from-catalog");
        var provider = new CapturingToolProvider(List.of(selectedTool));
        var aiProvider = new CapturingApiProvider();
        var model = sampleModel();
        var modelRegistry = new ModelRegistry();
        modelRegistry.register(model);
        var executor = new CronJobExecutor(
                new CampusClawAiService(new ApiProviderRegistry(List.of(aiProvider)), modelRegistry),
                modelRegistry,
                new CronRunLog(tempDir),
                List.of(new TestTool("spring-only")),
                null);
        executor.setToolProvider(provider);
        var payload = new CronPayload.AgentPrompt("run", null, model.id(), List.of("from-catalog"));
        var job = new CronJob(
                "job-1",
                "job",
                null,
                true,
                false,
                new CronSchedule.At(System.currentTimeMillis()),
                payload,
                CronJobState.initial(),
                System.currentTimeMillis());

        CronRunRecord record = executor.execute(job);

        assertThat(record.status()).isEqualTo(CronRunRecord.RunStatus.SUCCESS);
        assertThat(provider.allowedTools).containsExactly("from-catalog");
        assertThat(aiProvider.context.tools()).extracting("name").containsExactly("from-catalog");
    }

    private static Model sampleModel() {
        return new Model(
                "test-model",
                "Test Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(1.0, 2.0, 0.5, 0.25),
                200_000,
                4_096,
                null,
                null,
                null);
    }

    private static final class CapturingToolProvider implements ToolProvider {

        private final List<AgentTool> tools;
        private List<String> allowedTools;

        private CapturingToolProvider(List<AgentTool> tools) {
            this.tools = tools;
        }

        @Override
        public List<AgentTool> resolve(@Nullable List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return tools;
        }
    }

    private static final class CapturingApiProvider implements ApiProvider {

        private Context context;

        @Override
        public Api getApi() {
            return Api.ANTHROPIC_MESSAGES;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, @Nullable StreamOptions options) {
            throw new UnsupportedOperationException("Agent uses streamSimple");
        }

        @Override
        public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
            this.context = context;
            var stream = new AssistantMessageEventStream();
            var message = new AssistantMessage(
                    List.of(new TextContent("done")),
                    model.api().value(),
                    model.provider().value(),
                    model.id(),
                    null,
                    com.campusclaw.ai.types.Usage.empty(),
                    StopReason.STOP,
                    null,
                    System.currentTimeMillis());
            stream.push(new AssistantMessageEvent.DoneEvent(StopReason.STOP, message));
            return stream;
        }
    }

    private static final class TestTool implements AgentTool {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final String name;

        private TestTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(new TextContent("ok")), Map.of());
        }
    }
}
