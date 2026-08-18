/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentMetadata;
import com.campusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentMetadataSource;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 生产环境 {@link ChildAgentMetadataSource} 实现：优先读取子 Agent 本地的
 * {@code agentId.json} 快照；本地快照不存在时回退到只读的 GetAgentRuntime
 * 调用，不物化子 Agent 的运行时目录树。
 *
 * <p>任何失败都返回空 Optional，使 resolver 以 {@code UNKNOWN_CHILD}
 * fail closed，而不是把 IO 或协议错误抛给调用方。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class LocalChildAgentMetadataSource implements ChildAgentMetadataSource {

    private static final Logger log = LoggerFactory.getLogger(LocalChildAgentMetadataSource.class);

    private final AgentRuntimeProperties properties;
    private final MateServiceClient mateServiceClient;
    private final ObjectMapper mapper;

    public LocalChildAgentMetadataSource(
            AgentRuntimeProperties properties, MateServiceClient mateServiceClient, ObjectMapper mapper) {
        this.properties = properties;
        this.mateServiceClient = mateServiceClient;
        this.mapper = mapper;
    }

    /**
     * 加载子 Agent 元数据：先本地快照，后只读远端。
     *
     * @param agentId 子 Agent id
     * @return 元数据；任何来源都无法解析该子 Agent 时为空
     */
    @Override
    public Optional<ChildAgentMetadata> load(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        Optional<ChildAgentMetadata> local = loadFromSnapshot(agentId);
        if (local.isPresent()) {
            return local;
        }
        return loadFromRemote(agentId);
    }

    private Optional<ChildAgentMetadata> loadFromSnapshot(String agentId) {
        Path snapshot = properties
                .agentsRoot()
                .toAbsolutePath()
                .normalize()
                .resolve(agentId)
                .resolve(".campusclaw/agentId.json");
        if (!Files.isRegularFile(snapshot)) {
            return Optional.empty();
        }
        try {
            return Optional.of(toMetadata(mapper.readValue(snapshot.toFile(), AgentRuntime.class)));
        } catch (IOException e) {
            log.warn("unreadable local child snapshot for agent {}: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ChildAgentMetadata> loadFromRemote(String agentId) {
        try {
            return Optional.of(toMetadata(mateServiceClient.getAgentRuntime(agentId)));
        } catch (Exception e) {
            log.warn("child agent {} cannot be resolved remotely: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    private static ChildAgentMetadata toMetadata(AgentRuntime runtime) {
        // enabled 已在 AgentRuntime 构造器归一化(null 视为启用),此处直接取用
        return new ChildAgentMetadata(runtime.id(), runtime.version(), runtime.enabled());
    }
}
