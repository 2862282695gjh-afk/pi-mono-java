/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

// 无请求状态的输出策略；持久化始终由 Projector 在调用输出前完成。
enum PersistenceOnlyRuntimeEventOutput implements RuntimeEventOutput {
    INSTANCE;

    @Override
    public void emit(Supplier<RuntimeSseEventVO> event) {
        // 无响应流，不求值或保留事件工厂。
    }

    @Override
    public void emitBestEffort(Supplier<RuntimeSseEventVO> event) {
        // 无响应流，不构造流式预览。
    }

    @Override
    public void complete() {
        // 不持有连接或缓冲，无需释放输出资源。
    }
}
