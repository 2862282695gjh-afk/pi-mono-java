/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.result;

import com.campusclaw.codingagent.runtimeapi.vo.SuccessResponseVO;

/**
 * 独立运行环境的标准成功响应包装器。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class StandaloneResultBeanAdapter implements ResultBeanAdapter {
    @Override
    public <T> Object normal(T result) {
        return new SuccessResponseVO<>("0", "success", result);
    }
}
