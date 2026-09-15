/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.todo;

import java.util.List;

/**
 * TodoUpdate 成功结果携带的完整不可变清单快照。
 *
 * @param todos 有序完整任务列表
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record TodoSnapshotDTO(List<TodoItemDTO> todos) {
    public TodoSnapshotDTO {
        todos = List.copyOf(todos);
    }
}
