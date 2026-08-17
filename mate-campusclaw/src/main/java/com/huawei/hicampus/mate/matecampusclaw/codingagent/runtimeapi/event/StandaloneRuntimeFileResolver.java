/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.http.HttpStatus;

/**
 * 未接入公司文件服务时使用的安全适配器，只允许纯文本用户事件。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class StandaloneRuntimeFileResolver implements RuntimeFileResolver {
    @Override
    public List<ContentBlock> resolve(String sessionId, List<String> fileIds) {
        if (!fileIds.isEmpty()) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, RuntimeErrorCode.INVALID_EVENT_REQUEST);
        }
        return List.of();
    }
}
