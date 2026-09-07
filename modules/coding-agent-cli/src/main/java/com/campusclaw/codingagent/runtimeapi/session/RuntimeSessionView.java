/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.session;

/**
 * Session 相关业务响应及其可选强 ETag 的 Service 返回值；该载体不作为 JSON 结果序列化。
 *
 * @param <T> 接口结果 VO 类型
 * @param resource 对外业务响应 VO
 * @param etag 完整 Session 资源的强 ETag；其他业务结果为 null
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeSessionView<T>(T resource, String etag) {}
