/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command;

import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionResult;

/**
 * 四个保留命令所需的宿主无关 Session 操作端口。
 *
 * <p>首版 Runtime 不提供该端口实现，也不会注册 Slash Command。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public interface SlashCommandSession {
    String currentModelId();

    void changeModel(String modelId);

    boolean thinkingEnabled();

    void changeThinking(boolean enabled);

    SessionCompactionResult compact(String customInstructions);

    Optional<String> displayName();

    void changeDisplayName(String name);
}
