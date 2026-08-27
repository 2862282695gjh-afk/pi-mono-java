/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client.mate;

import java.util.List;
import java.util.Map;

/**
 * Mate 工具发现和执行客户端。发现方法不接收执行凭据，执行方法只接受内部工具标识并显式
 * 接收本次执行的凭据快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
public interface MateToolClient {

    /**
     * 查询指定 Agent 直接绑定的工具，并恢复绑定顺序。
     *
     * @param agentId Agent 标识
     * @return 按绑定顺序排列的工具元数据
     */
    List<MateToolMeta> listAgentTools(String agentId);

    /**
     * 查询指定 Skill 直接绑定的工具，并恢复绑定顺序。
     *
     * @param skillId Skill 标识
     * @return 按绑定顺序排列的工具元数据
     */
    List<MateToolMeta> listSkillTools(String skillId);

    /**
     * 使用 Agent 下发凭据调用指定内部工具标识。
     *
     * @param toolId 工具标识
     * @param args 工具参数
     * @param credentials 透传给 Mate 的凭据
     * @return 工具执行结果
     */
    ToolResult callTool(String toolId, Map<String, Object> args, MateCredentials credentials);

    /**
     * 工具执行结果。
     *
     * @param content 工具返回的文本
     * @param metadata 可选元数据
     * @param isError 是否为错误结果
     */
    record ToolResult(String content, Map<String, Object> metadata, boolean isError) {}
}
