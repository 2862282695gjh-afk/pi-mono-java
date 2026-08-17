/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.result;

import com.campusclaw.codingagent.runtimeapi.vo.SuccessResponseVO;

/**
 * 独立开发环境中与公司 ResultBeanFactory 调用体验一致的最小工厂。
 *
 * <p>公司构建 Profile 可在适配层把该工厂替换为内部制品；业务 Service 不依赖内部
 * Maven 坐标。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public final class ResultBeanFactory {
    private static final ResultBeanFactory INSTANCE = new ResultBeanFactory();

    private ResultBeanFactory() {}

    public static ResultBeanFactory getFactory() {
        return INSTANCE;
    }

    public <T> SuccessResponseVO<T> normal(T result) {
        return new SuccessResponseVO<>("0", "success", result);
    }
}
