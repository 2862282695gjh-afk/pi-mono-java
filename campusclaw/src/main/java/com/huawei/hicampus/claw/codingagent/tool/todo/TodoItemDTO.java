/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.todo;

/**
 * TodoUpdate 规范化后的单项只读快照。
 *
 * @param content 规范化任务内容
 * @param status 任务状态
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record TodoItemDTO(String content, String status) {}
