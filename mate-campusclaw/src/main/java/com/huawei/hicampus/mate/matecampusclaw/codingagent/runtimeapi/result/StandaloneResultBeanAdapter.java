/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.SuccessResponseVO;

/**
 * 独立运行环境的标准成功响应包装器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class StandaloneResultBeanAdapter implements ResultBeanAdapter {
    @Override
    public <T> Object normal(T result) {
        return new SuccessResponseVO<>("0", "success", result);
    }
}
