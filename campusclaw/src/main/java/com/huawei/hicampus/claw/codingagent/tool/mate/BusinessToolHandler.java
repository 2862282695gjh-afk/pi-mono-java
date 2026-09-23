/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import java.util.Map;
import java.util.Objects;

import com.huawei.hicampus.claw.agent.tool.PreparedToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 把一个已完成可信解析和参数校验的业务工具调用固定为可执行快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface BusinessToolHandler {

    PreparedToolInvocation prepare(InvocationDTO invocation);

    /**
     * 保存分派前已验证的工具身份和参数快照。
     *
     * @param toolCallId 模型工具调用标识
     * @param toolName 模型可见的入口名称
     * @param arguments 模型入口参数
     * @param targetId 可信业务工具标识
     * @param targetName 可信业务工具名称
     * @param targetVersion 可信业务工具版本
     * @param targetDescription 可信业务工具描述
     * @param inputSchema 可信业务工具输入定义
     * @param searchableDefinition 是否可通过 SearchTools 重新获取完整定义
     * @param businessArguments 已按业务 Schema 校验的参数
     */
    record InvocationDTO(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            String targetId,
            String targetName,
            String targetVersion,
            String targetDescription,
            JsonNode inputSchema,
            boolean searchableDefinition,
            Map<String, Object> businessArguments) {
        public InvocationDTO {
            Objects.requireNonNull(toolCallId, "toolCallId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(inputSchema, "inputSchema");
            arguments = PreparedToolInvocation.snapshotArguments(arguments);
            inputSchema = inputSchema.deepCopy();
            businessArguments = PreparedToolInvocation.snapshotArguments(businessArguments);
        }

        @Override
        public Map<String, Object> arguments() {
            return PreparedToolInvocation.snapshotArguments(arguments);
        }

        @Override
        public Map<String, Object> businessArguments() {
            return PreparedToolInvocation.snapshotArguments(businessArguments);
        }

        @Override
        public JsonNode inputSchema() {
            return inputSchema.deepCopy();
        }
    }
}
