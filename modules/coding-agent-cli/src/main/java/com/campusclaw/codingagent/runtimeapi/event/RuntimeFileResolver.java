/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.List;

import com.campusclaw.ai.types.ContentBlock;

/**
 * 校验 Session 文件引用并转换为 Agent 输入内容的公司集成端口。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public interface RuntimeFileResolver {
    List<ContentBlock> resolve(String sessionId, List<String> fileIds);
}
