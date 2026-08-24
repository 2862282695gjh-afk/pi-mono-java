/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;

/**
 * 从压缩前消息中提取 Read 工具读取过的文件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class FileOperationTracker {
    private FileOperationTracker() {}

    public static Set<String> filesRead(List<Message> messages) {
        Set<String> result = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.content().stream()
                        .filter(ToolCall.class::isInstance)
                        .map(ToolCall.class::cast)
                        .filter(call -> "Read".equals(call.name()))
                        .map(ToolCall::arguments)
                        .map(FileOperationTracker::readPath)
                        .filter(path -> path != null && !path.isBlank())
                        .forEach(result::add);
            }
        }
        return Set.copyOf(result);
    }

    private static String readPath(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get("path");
        return value instanceof String path ? path : null;
    }
}
