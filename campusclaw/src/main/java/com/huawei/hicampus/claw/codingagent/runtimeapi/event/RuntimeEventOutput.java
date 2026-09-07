/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

/**
 * 活动执行的可选公共事件输出，与权威 Entry 持久化相互独立。
 *
 * <p>输出实现同步求值或丢弃事件工厂，不得保留工厂到后台执行。
 * 无输出模式不构造公共事件、不申请请求缓冲，也不拥有执行取消权。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeEventOutput {
    void emit(Supplier<RuntimeSseEventVO> event);

    void emitBestEffort(Supplier<RuntimeSseEventVO> event);

    void complete();

    static RuntimeEventOutput persistenceOnly() {
        return PersistenceOnlyRuntimeEventOutput.INSTANCE;
    }
}
