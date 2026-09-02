/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.session;

/**
 * Session 资源响应及其强 ETag 的 Service 返回值。
 *
 * @param <T> 接口结果 VO 类型
 * @param resource 对外 Session 资源
 * @param etag 强 ETag
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeSessionView<T>(T resource, String etag) {}
