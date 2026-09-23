/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.PreparedToolInvocation;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;

/**
 * 执行普通 Mate 远端业务工具的处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
public final class MateRemoteToolHandler implements BusinessToolHandler {
    private final MateToolClient client;

    private final MateCredentials credentials;

    MateRemoteToolHandler(MateToolClient client, MateCredentials credentials) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public PreparedToolInvocation prepare(InvocationDTO invocation) {
        return new RemoteInvocation(this, invocation);
    }

    private AgentToolResult execute(InvocationDTO invocation, CancellationToken signal) throws Exception {
        ensureNotCancelled(signal);
        MateToolClient.ToolResult result =
                client.callTool(invocation.targetId(), invocation.businessArguments(), credentials);
        if (result.isError()) {
            throw new MateToolExecutionException(invocation.targetName(), result.content());
        }
        String content = result.content() == null ? "" : result.content();
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(content)), result.metadata());
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }

    private record RemoteInvocation(MateRemoteToolHandler handler, InvocationDTO invocation)
            implements PreparedToolInvocation {

        @Override
        public String toolCallId() {
            return invocation.toolCallId();
        }

        @Override
        public String toolName() {
            return invocation.toolName();
        }

        @Override
        public Map<String, Object> arguments() {
            return invocation.arguments();
        }

        @Override
        public String authorizationTargetName() {
            return invocation.targetName();
        }

        @Override
        public String authorizationTargetId() {
            return invocation.targetId();
        }

        @Override
        public AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) throws Exception {
            return handler.execute(invocation, signal);
        }
    }
}
