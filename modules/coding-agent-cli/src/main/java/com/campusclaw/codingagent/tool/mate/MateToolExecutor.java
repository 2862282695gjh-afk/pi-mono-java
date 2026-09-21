/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolvedTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/**
 * 使用可信绑定统一解析、校验并执行 Agent 或 Skill 来源的 Mate 业务工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolExecutor {

    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final MateToolClient client;

    private final MateCredentials credentials;

    private final MateToolResolver resolver;

    MateToolExecutor(MateToolClient client, MateCredentials credentials, MateToolResolver resolver) {
        this.client = client;
        this.credentials = credentials;
        this.resolver = resolver;
    }

    public List<AgentTool> createAgentToolAdapters() {
        return resolver.resolveAgentTools().stream()
                .map(tool -> (AgentTool) new MateToolAdapter(this, tool))
                .toList();
    }

    public AgentToolResult executeAgent(String name, Map<String, Object> args, CancellationToken signal)
            throws Exception {
        return execute(resolver.resolveAgent(name), args, signal);
    }

    public AgentToolResult executeSkill(String name, Map<String, Object> args, CancellationToken signal)
            throws Exception {
        return execute(resolver.resolveSkill(name), args, signal);
    }

    private AgentToolResult execute(ResolvedTool tool, Map<String, Object> args, CancellationToken signal)
            throws Exception {
        ensureNotCancelled(signal);
        validateArguments(tool, args);
        ensureNotCancelled(signal);
        MateToolClient.ToolResult result = client.callTool(tool.binding().id(), args, credentials);
        if (result.isError()) {
            throw new MateToolExecutionException(tool.binding().name(), result.content());
        }
        String content = result.content() == null ? "" : result.content();
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(content)), result.metadata());
    }

    private void validateArguments(ResolvedTool tool, Map<String, Object> args) {
        try {
            var schema =
                    schemaFactory.getSchema(mapper.valueToTree(tool.metadata().inputSchema()));
            var errors = schema.validate(mapper.valueToTree(args));
            if (!errors.isEmpty()) {
                String detail = errors.stream()
                        .map(Object::toString)
                        .sorted()
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown schema validation error");
                throw new MateToolExecutionException(tool.binding().name(), "arguments failed validation: " + detail);
            }
        } catch (MateToolExecutionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MateToolExecutionException(tool.binding().name(), "business input schema is invalid");
        }
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }
}
