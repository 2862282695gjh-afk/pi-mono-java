/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import java.util.List;

import com.campusclaw.agent.tool.AfterToolCallContext;
import com.campusclaw.agent.tool.AfterToolCallHandler;
import com.campusclaw.agent.tool.AfterToolCallResult;
import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallHandler;
import com.campusclaw.agent.tool.BeforeToolCallResult;

/**
 * 将创建 Session 时固定的有序 hook 列表组合为单个 core hook。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
final class AgentSessionHookChain {

    private AgentSessionHookChain() {}

    static BeforeToolCallHandler before(List<BeforeToolCallHandler> hooks) {
        List<BeforeToolCallHandler> chain = List.copyOf(hooks);
        return context -> executeBefore(chain, context);
    }

    static AfterToolCallHandler after(List<AfterToolCallHandler> hooks) {
        List<AfterToolCallHandler> chain = List.copyOf(hooks);
        return context -> executeAfter(chain, context);
    }

    private static BeforeToolCallResult executeBefore(List<BeforeToolCallHandler> hooks, BeforeToolCallContext context)
            throws Exception {
        for (BeforeToolCallHandler hook : hooks) {
            BeforeToolCallResult result = hook.handle(context);
            if (result != null && result.block()) {
                return result;
            }
        }
        return BeforeToolCallResult.allow();
    }

    private static AfterToolCallResult executeAfter(List<AfterToolCallHandler> hooks, AfterToolCallContext context)
            throws Exception {
        AfterToolCallResult combined = AfterToolCallResult.noOverride();
        for (AfterToolCallHandler hook : hooks) {
            combined = merge(combined, hook.handle(context));
        }
        return combined;
    }

    private static AfterToolCallResult merge(AfterToolCallResult first, AfterToolCallResult next) {
        if (next == null) {
            return first;
        }
        return new AfterToolCallResult(
                next.content() != null ? next.content() : first.content(),
                next.details() != null ? next.details() : first.details(),
                next.isError() != null ? next.isError() : first.isError());
    }
}
