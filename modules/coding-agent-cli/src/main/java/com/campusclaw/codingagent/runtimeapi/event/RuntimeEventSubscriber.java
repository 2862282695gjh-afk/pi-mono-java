/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

/**
 * 单次 Runtime SSE 请求的下游写入端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeEventSubscriber {
    void onEvent(RuntimeSseEventVO event);

    void onHeartbeat();

    void onComplete();

    void onError(Throwable error);
}
