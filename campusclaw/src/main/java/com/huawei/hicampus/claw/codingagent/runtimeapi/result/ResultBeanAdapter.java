/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.result;

/**
 * 隔离公司 ResultBeanFactory 制品的成功响应包装端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ResultBeanAdapter {
    /**
     * 包装普通成功结果。
     *
     * @param result 业务结果
     * @param <T> 业务结果类型
     * @return 公司普通成功响应对象
     */
    <T> Object normal(T result);
}
