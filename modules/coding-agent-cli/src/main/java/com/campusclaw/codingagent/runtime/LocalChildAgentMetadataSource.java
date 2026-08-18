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
 * Production {@link ChildAgentMetadataSource}: reads the child's local
 * {@code agentId.json} snapshot first and, when no local snapshot exists,
 * falls back to a read-only GetAgentRuntime call that does not materialize
 * the child's runtime tree.
 *
 * <p>Any failure resolves to an empty Optional so the resolver fails closed
 * with {@code UNKNOWN_CHILD} instead of surfacing IO or protocol errors.
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
     * Loads child metadata, local snapshot first, remote read-only second.
     *
     * @param agentId child Agent identifier
     * @return metadata, or empty when the child cannot be resolved anywhere
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
        boolean enabled = runtime.enabled() == null || runtime.enabled();
        return new ChildAgentMetadata(runtime.id(), runtime.version(), enabled);
    }
}
