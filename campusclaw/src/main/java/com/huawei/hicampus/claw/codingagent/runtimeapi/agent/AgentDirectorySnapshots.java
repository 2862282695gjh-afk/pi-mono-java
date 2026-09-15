/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import java.util.LinkedHashSet;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

/**
 * 将同一次准备得到的受管 Agent 运行时转换为模型解析快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public final class AgentDirectorySnapshots {
    private AgentDirectorySnapshots() {}

    /**
     * 转换并校验已准备的 Agent 运行时，不再次访问目录或远端服务。
     *
     * @param runtime 已准备的受管 Agent 运行时
     * @return 可用于 RuntimeModelManager 的不可变快照
     * @throws RuntimeApiException Agent 已禁用或模型绑定无效时抛出
     */
    public static AgentDirectorySnapshotDTO from(PreparedAgentRuntime runtime) {
        if (!Boolean.TRUE.equals(runtime.metadata().enabled())) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        String defaultModel = runtime.metadata()
                .defaultModel()
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
        List<String> models = validModels(runtime.metadata().bindingModels(), defaultModel);
        return new AgentDirectorySnapshotDTO(
                runtime.agentId(),
                defaultModel,
                models,
                runtime.metadata().mode(),
                runtime.agentRoot(),
                runtime.agentRoot().resolve(".campusclaw"));
    }

    private static List<String> validModels(List<String> configured, String defaultModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (String model : configured) {
            if (model == null || model.isBlank() || !models.add(model)) {
                throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
            }
        }
        if (!models.contains(defaultModel)) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        return List.copyOf(models);
    }
}
