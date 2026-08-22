/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;

/**
 * Mate 工具对工厂：为每个 agent 会话产出一对持有独立会话缓存的工具。
 *
 * <p>工具名→标识映射是会话私有状态（不同 agent 绑定的工具列表不同），
 * 因此 listMateTool 与 callMateTool 必须随会话成组创建：每次
 * {@link #create()} 返回的两个工具共享一个<b>新建的</b>
 * {@link MateToolSessionCache}——组内共享（list 刷新的映射 call 能读到），
 * 组间隔离（A 会话的缓存不会被 B 会话覆盖）。会话组装点
 * （{@code AgentSession} 工具集装配 / runtime session engine）应每会话
 * 调用一次本方法，并把返回的工具追加进该会话的工具列表。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolsetFactory {

    private final MateToolClient client;

    private final MateCredentialResolver credentialResolver;

    /**
     * 创建工厂。
     *
     * @param client Mate 工具服务客户端（无状态，可跨会话共享）
     * @param credentialResolver 按调用解析凭据的提供者；null 时
     *        callMateTool 以 fail-closed 拒绝执行
     */
    public MateToolsetFactory(MateToolClient client, MateCredentialResolver credentialResolver) {
        this.client = client;
        this.credentialResolver = credentialResolver;
    }

    /**
     * 为一个会话创建一对 Mate 工具。
     *
     * @return listMateTool 与 callMateTool（共享一个新建的会话缓存）
     */
    public List<AgentTool> create() {
        MateToolSessionCache sessionCache = new MateToolSessionCache();
        return List.of(
                new ListMateTool(client, sessionCache), new CallMateTool(client, credentialResolver, sessionCache));
    }
}
