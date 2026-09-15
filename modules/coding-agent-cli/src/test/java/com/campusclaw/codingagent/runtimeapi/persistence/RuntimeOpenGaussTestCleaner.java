/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 以一个语句清理带外键的完整 Runtime 测试表集合。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
final class RuntimeOpenGaussTestCleaner {
    private static final List<String> TABLES = List.of(
            "t_session_event_projection",
            "t_session_question_answers",
            "t_session_events",
            "t_session_materialized",
            "t_session_stats",
            "t_session_records",
            "t_session_sequences",
            "t_session_plan_confirmations",
            "t_session_tool_confirmations",
            "t_session_execution_segment_events",
            "t_session_execution_segments",
            "t_session_executions",
            "t_session_entries",
            "t_session_cleanup_task",
            "t_session_tombstone",
            "t_sessions");

    private RuntimeOpenGaussTestCleaner() {}

    static void truncate(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES));
    }
}
