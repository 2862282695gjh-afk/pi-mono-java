/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

/**
 * 仅需持久化、无需请求响应流的无状态事件输出策略。
 *
 * <p>持久化由 Projector 在调用输出前完成；本策略直接丢弃事件工厂，不求值、不保留，
 * 因而不构造 SSE 事件，也不持有请求连接或缓冲。输出完成不影响活动执行的完成或取消状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
enum PersistenceOnlyRuntimeEventOutput implements RuntimeEventOutput {
    INSTANCE;

    @Override
    public void emit(Supplier<RuntimeSseEventVO> event) {
        // 无响应流，不求值或保留事件工厂。
    }

    @Override
    public boolean emitBestEffort(Supplier<RuntimeSseEventVO> event) {
        // 无响应流，不构造流式预览。
        return false;
    }

    @Override
    public void complete() {
        // 不持有连接或缓冲，无需释放输出资源。
    }
}
