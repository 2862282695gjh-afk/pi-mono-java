-- CampusClaw Events v2 authority and one-time migration input schema.
-- Run only through the database release platform while Session writes are stopped.

BEGIN;

CREATE TABLE IF NOT EXISTS t_session_events (
    session_id      VARCHAR(128)   NOT NULL,
    event_id        VARCHAR(128)   NOT NULL,
    event_seq       BIGINT         NOT NULL,
    anchor_entry_id VARCHAR(128)   NOT NULL,
    type            VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    payload         JSONB          NOT NULL,
    PRIMARY KEY (session_id, event_id)
);

COMMENT ON TABLE t_session_events IS '会话公共事件权威表，保存 GET 历史与 POST 完整帧共享的安全投影';
COMMENT ON COLUMN t_session_events.session_id IS '这条公共事件属于哪个会话；对应 t_sessions.id';
COMMENT ON COLUMN t_session_events.event_id IS '对外公开且永不复用的稳定事件标识';
COMMENT ON COLUMN t_session_events.event_seq IS '公共事件与 Entry、内部 Record 共享的会话提交顺序号';
COMMENT ON COLUMN t_session_events.anchor_entry_id IS '公共事件所属的分支 Entry 标识，用于判断当前路径可见性';
COMMENT ON COLUMN t_session_events.type IS '完整公共事件的 v2 类型字面值';
COMMENT ON COLUMN t_session_events.created_at IS '形成权威公共记录时保存一次的 UTC 毫秒时间';
COMMENT ON COLUMN t_session_events.payload IS '只包含该公共事件安全业务字段的 v2 JSON 对象';

CREATE UNIQUE INDEX IF NOT EXISTS idx_t_session_events_session_seq
    ON t_session_events (session_id, event_seq);

CREATE INDEX IF NOT EXISTS idx_t_session_events_session_anchor
    ON t_session_events (session_id, anchor_entry_id, event_seq);

CREATE TABLE IF NOT EXISTS t_session_event_projection (
    session_id      VARCHAR(128) NOT NULL,
    anchor_entry_id VARCHAR(128) NOT NULL,
    event_count     INTEGER      NOT NULL,
    mapping_source  VARCHAR(16)  NOT NULL,
    PRIMARY KEY (session_id, anchor_entry_id),
    CONSTRAINT ck_session_event_projection_count CHECK (event_count >= 0),
    CONSTRAINT ck_session_event_projection_source CHECK (mapping_source IN ('runtime', 'migration'))
);

COMMENT ON TABLE t_session_event_projection IS '会话 Entry 公共事件投影完整性记录，用精确数量区分完整映射、显式私有记录和迁移缺口';
COMMENT ON COLUMN t_session_event_projection.session_id IS '被核验 Entry 所属的会话标识';
COMMENT ON COLUMN t_session_event_projection.anchor_entry_id IS '已完成公共投影核验的 Entry 标识';
COMMENT ON COLUMN t_session_event_projection.event_count IS '该 Entry 应有的完整公共事件精确数量；零表示经确认不公开';
COMMENT ON COLUMN t_session_event_projection.mapping_source IS '完整性结论来源；runtime 表示新写入，migration 表示经升级迁移审核';

CREATE TABLE IF NOT EXISTS t_session_event_migration_review (
    session_id      VARCHAR(128) NOT NULL,
    anchor_entry_id VARCHAR(128) NOT NULL,
    event_count     INTEGER      NOT NULL,
    mapping_reason  VARCHAR(256) NOT NULL,
    PRIMARY KEY (session_id, anchor_entry_id),
    CONSTRAINT ck_session_event_migration_review_count CHECK (event_count >= 0),
    CONSTRAINT ck_session_event_migration_review_reason CHECK (LENGTH(BTRIM(mapping_reason)) > 0)
);

COMMENT ON TABLE t_session_event_migration_review IS 'Events v2 一次迁移的人工审核输入，只记录旧 Entry 的精确公共事件数量';
COMMENT ON COLUMN t_session_event_migration_review.session_id IS '经审核旧 Entry 所属的会话标识';
COMMENT ON COLUMN t_session_event_migration_review.anchor_entry_id IS '经审核旧 Entry 标识';
COMMENT ON COLUMN t_session_event_migration_review.event_count IS '审核确认的完整公共事件精确数量；零表示明确不公开';
COMMENT ON COLUMN t_session_event_migration_review.mapping_reason IS '不含正文或凭据的审核理由，说明公开映射或明确私有分类依据';

CREATE TABLE IF NOT EXISTS t_session_event_migration_events (
    session_id      VARCHAR(128)   NOT NULL,
    anchor_entry_id VARCHAR(128)   NOT NULL,
    event_order     INTEGER        NOT NULL,
    event_id        VARCHAR(128)   NOT NULL,
    type            VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    payload         JSONB          NOT NULL,
    PRIMARY KEY (session_id, anchor_entry_id, event_order),
    CONSTRAINT uk_session_event_migration_event_id UNIQUE (session_id, event_id),
    CONSTRAINT ck_session_event_migration_order CHECK (event_order > 0),
    CONSTRAINT ck_session_event_migration_id CHECK (LENGTH(BTRIM(event_id)) > 0)
);

COMMENT ON TABLE t_session_event_migration_events IS 'Events v2 一次迁移的人工审核公共事件输入，成功迁移后删除内容';
COMMENT ON COLUMN t_session_event_migration_events.session_id IS '待迁移公共事件所属的会话标识';
COMMENT ON COLUMN t_session_event_migration_events.anchor_entry_id IS '待迁移公共事件对应的旧 Entry 标识';
COMMENT ON COLUMN t_session_event_migration_events.event_order IS '同一旧 Entry 内公共事件的审核顺序，从一开始且连续';
COMMENT ON COLUMN t_session_event_migration_events.event_id IS '审核后固定的公共事件标识，迁移重跑时不得重新生成';
COMMENT ON COLUMN t_session_event_migration_events.type IS '审核后的 v2 公共事件类型';
COMMENT ON COLUMN t_session_event_migration_events.created_at IS '审核后固定的 UTC 毫秒事件时间';
COMMENT ON COLUMN t_session_event_migration_events.payload IS '审核后仅含安全公共字段的 v2 JSON 对象';

CREATE OR REPLACE FUNCTION f_session_event_has_exact_keys(
    value JSONB,
    required_keys TEXT[],
    optional_keys TEXT[]
) RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        JSONB_TYPEOF(value) = 'object'
        AND NOT EXISTS (
            SELECT 1
            FROM UNNEST(required_keys) AS required_key
            WHERE NOT value ? required_key
        )
        AND NOT EXISTS (
            SELECT 1
            FROM JSONB_OBJECT_KEYS(value) AS actual_key
            WHERE NOT actual_key = ANY(required_keys || optional_keys)
        ),
        FALSE
    );
$$;

COMMENT ON FUNCTION f_session_event_has_exact_keys(JSONB, TEXT[], TEXT[]) IS '校验事件 JSON 对象只含精确的必填和可选字段';

CREATE OR REPLACE FUNCTION f_session_event_is_java_blank(value TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT value IS NULL OR COALESCE(LENGTH(REGEXP_REPLACE(
        value,
        U&'[\0009-\000D\001C-\001F\0020\1680\2000-\2006\2008-\200A\2028-\2029\205F\3000]',
        '',
        'g'
    )), 0) = 0;
$$;

COMMENT ON FUNCTION f_session_event_is_java_blank(TEXT) IS '按 Java Character.isWhitespace 语义判断字符串是否全为空白';

CREATE OR REPLACE FUNCTION f_session_event_has_string(
    value JSONB,
    field_name TEXT,
    allow_empty BOOLEAN
) RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        JSONB_TYPEOF(value -> field_name) = 'string'
        AND (allow_empty OR NOT f_session_event_is_java_blank(value ->> field_name)),
        FALSE
    );
$$;

COMMENT ON FUNCTION f_session_event_has_string(JSONB, TEXT, BOOLEAN) IS '校验事件 JSON 字符串字段及空白约束';

CREATE OR REPLACE FUNCTION f_session_event_has_nonnegative_long(value JSONB, field_name TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        JSONB_TYPEOF(value -> field_name) = 'number'
        AND value ->> field_name ~ '^(0|[1-9][0-9]*)$'
        AND (value ->> field_name)::NUMERIC <= 9223372036854775807,
        FALSE
    );
$$;

COMMENT ON FUNCTION f_session_event_has_nonnegative_long(JSONB, TEXT) IS '校验事件 JSON 字段为 Java long 范围内的非负整数';

CREATE OR REPLACE FUNCTION f_session_event_has_nonnegative_number(value JSONB, field_name TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        JSONB_TYPEOF(value -> field_name) = 'number'
        AND (value ->> field_name)::NUMERIC >= 0,
        FALSE
    );
$$;

COMMENT ON FUNCTION f_session_event_has_nonnegative_number(JSONB, TEXT) IS '校验事件 JSON 字段为非负数值';

CREATE OR REPLACE FUNCTION f_session_event_utf16_length(value TEXT)
RETURNS BIGINT
LANGUAGE SQL
IMMUTABLE
AS $$
    WITH encoded AS (
        SELECT ENCODE(CONVERT_TO(value, 'UTF8'), 'escape') AS escaped_value
    )
    SELECT (LENGTH(value)
        + (LENGTH(escaped_value)
            - LENGTH(REGEXP_REPLACE(escaped_value, E'\\\\36[0-4]', '', 'g'))) / 4)::BIGINT
    FROM encoded;
$$;

COMMENT ON FUNCTION f_session_event_utf16_length(TEXT) IS '按 Java String 的 UTF-16 单元计算公共字符串长度';

CREATE OR REPLACE FUNCTION f_session_event_validate_user_content(content JSONB)
RETURNS BOOLEAN
LANGUAGE PLPGSQL
IMMUTABLE
AS $$
DECLARE
    block JSONB;
    block_index INTEGER := 0;
    file_ids TEXT[] := ARRAY[]::TEXT[];
    file_id TEXT;
    has_text BOOLEAN := FALSE;
BEGIN
    IF JSONB_TYPEOF(content) != 'array' OR JSONB_ARRAY_LENGTH(content) NOT BETWEEN 1 AND 5 THEN
        RETURN FALSE;
    END IF;
    FOR block IN SELECT value FROM JSONB_ARRAY_ELEMENTS(content)
    LOOP
        block_index := block_index + 1;
        IF block ->> 'type' = 'text' THEN
            IF block_index != 1 OR has_text
                    OR NOT f_session_event_has_exact_keys(block, ARRAY['type', 'text'], ARRAY[]::TEXT[])
                    OR NOT f_session_event_has_string(block, 'text', FALSE)
                    OR f_session_event_utf16_length(block ->> 'text') > 262144 THEN
                RETURN FALSE;
            END IF;
            has_text := TRUE;
        ELSIF block ->> 'type' = 'file' THEN
            file_id := block ->> 'fileId';
            IF NOT f_session_event_has_exact_keys(block, ARRAY['type', 'fileId'], ARRAY[]::TEXT[])
                    OR JSONB_TYPEOF(block -> 'fileId') != 'string'
                    OR COALESCE(file_id !~ '^[0-9a-fA-F]{32}$', TRUE)
                    OR file_id = ANY(file_ids)
                    OR COALESCE(ARRAY_LENGTH(file_ids, 1), 0) >= 4 THEN
                RETURN FALSE;
            END IF;
            file_ids := ARRAY_APPEND(file_ids, file_id);
        ELSE
            RETURN FALSE;
        END IF;
    END LOOP;
    RETURN TRUE;
END;
$$;

COMMENT ON FUNCTION f_session_event_validate_user_content(JSONB) IS '校验用户消息的文本和文件公共内容块';

CREATE OR REPLACE FUNCTION f_session_event_validate_text_content(content JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(
        JSONB_TYPEOF(content) = 'array'
        AND JSONB_ARRAY_LENGTH(content) > 0
        AND NOT EXISTS (
            SELECT 1
            FROM JSONB_ARRAY_ELEMENTS(content) AS block
            WHERE NOT f_session_event_has_exact_keys(block, ARRAY['type', 'text'], ARRAY[]::TEXT[])
               OR block ->> 'type' != 'text'
               OR NOT f_session_event_has_string(block, 'text', TRUE)
        ),
        FALSE
    );
$$;

COMMENT ON FUNCTION f_session_event_validate_text_content(JSONB) IS '校验工具结果的非空文本内容块数组';

CREATE OR REPLACE FUNCTION f_session_event_validate_cost(cost JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT f_session_event_has_exact_keys(
        cost,
        ARRAY['input', 'output', 'cacheRead', 'cacheWrite', 'total'],
        ARRAY[]::TEXT[]
    )
    AND f_session_event_has_nonnegative_number(cost, 'input')
    AND f_session_event_has_nonnegative_number(cost, 'output')
    AND f_session_event_has_nonnegative_number(cost, 'cacheRead')
    AND f_session_event_has_nonnegative_number(cost, 'cacheWrite')
    AND f_session_event_has_nonnegative_number(cost, 'total');
$$;

COMMENT ON FUNCTION f_session_event_validate_cost(JSONB) IS '校验 Agent 消息 Usage 的可选费用对象';

CREATE OR REPLACE FUNCTION f_session_event_validate_usage(usage JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT f_session_event_has_exact_keys(
        usage,
        ARRAY['input', 'output', 'cacheRead', 'cacheWrite', 'totalTokens'],
        ARRAY['cost']
    )
    AND f_session_event_has_nonnegative_long(usage, 'input')
    AND f_session_event_has_nonnegative_long(usage, 'output')
    AND f_session_event_has_nonnegative_long(usage, 'cacheRead')
    AND f_session_event_has_nonnegative_long(usage, 'cacheWrite')
    AND f_session_event_has_nonnegative_long(usage, 'totalTokens')
    AND (NOT usage ? 'cost' OR f_session_event_validate_cost(usage -> 'cost'));
$$;

COMMENT ON FUNCTION f_session_event_validate_usage(JSONB) IS '校验 Agent 完整消息的可选 Usage 对象';

CREATE OR REPLACE FUNCTION f_validate_session_event_v2(event_type VARCHAR, payload JSONB)
RETURNS BOOLEAN
LANGUAGE PLPGSQL
IMMUTABLE
AS $$
DECLARE
    result_value TEXT;
    error_code TEXT;
    reason_value TEXT;
BEGIN
    IF event_type = 'user.message' THEN
        RETURN COALESCE(
            f_session_event_has_exact_keys(payload, ARRAY['content'], ARRAY[]::TEXT[])
            AND f_session_event_validate_user_content(payload -> 'content'),
            FALSE
        );
    ELSIF event_type = 'user.interrupt' THEN
        RETURN COALESCE(
            f_session_event_has_exact_keys(payload, ARRAY['targetEventId'], ARRAY[]::TEXT[])
            AND f_session_event_has_string(payload, 'targetEventId', FALSE),
            FALSE
        );
    ELSIF event_type = 'user.tool_confirmation' THEN
        result_value := payload ->> 'result';
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['toolCallId', 'result'], ARRAY['denyMessage'])
            AND f_session_event_has_string(payload, 'toolCallId', FALSE)
            AND result_value IN ('allow', 'deny')
            AND (result_value != 'allow' OR NOT payload ? 'denyMessage')
            AND (NOT payload ? 'denyMessage'
                OR (f_session_event_has_string(payload, 'denyMessage', FALSE)
                    AND f_session_event_utf16_length(payload ->> 'denyMessage') <= 4096)), FALSE);
    ELSIF event_type = 'agent.message' THEN
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['phase', 'content', 'sourceEventId'], ARRAY['usage'])
            AND payload ->> 'phase' = 'completed'
            AND f_session_event_has_string(payload, 'content', TRUE)
            AND f_session_event_has_string(payload, 'sourceEventId', FALSE)
            AND (NOT payload ? 'usage' OR f_session_event_validate_usage(payload -> 'usage')), FALSE);
    ELSIF event_type = 'agent.thinking' THEN
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['phase', 'content', 'sourceEventId'], ARRAY[]::TEXT[])
            AND payload ->> 'phase' = 'completed'
            AND f_session_event_has_string(payload, 'content', TRUE)
            AND f_session_event_has_string(payload, 'sourceEventId', FALSE), FALSE);
    ELSIF event_type = 'agent.tool_call' THEN
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload,
                ARRAY['toolCallId', 'toolName', 'arguments', 'requiresConfirmation', 'sourceEventId'],
                ARRAY[]::TEXT[])
            AND f_session_event_has_string(payload, 'toolCallId', FALSE)
            AND f_session_event_has_string(payload, 'toolName', FALSE)
            AND JSONB_TYPEOF(payload -> 'arguments') = 'object'
            AND JSONB_TYPEOF(payload -> 'requiresConfirmation') = 'boolean'
            AND f_session_event_has_string(payload, 'sourceEventId', FALSE)
            AND (payload ->> 'toolName' != 'CallMateTool'
                OR (f_session_event_has_exact_keys(
                        payload -> 'arguments', ARRAY['tool', 'args'], ARRAY[]::TEXT[])
                    AND f_session_event_has_string(payload -> 'arguments', 'tool', FALSE)
                    AND JSONB_TYPEOF(payload -> 'arguments' -> 'args') = 'object')), FALSE);
    ELSIF event_type = 'agent.tool_result' THEN
        error_code := payload ->> 'errorCode';
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['toolCallId', 'content', 'isError', 'sourceEventId'], ARRAY['errorCode'])
            AND f_session_event_has_string(payload, 'toolCallId', FALSE)
            AND f_session_event_validate_text_content(payload -> 'content')
            AND JSONB_TYPEOF(payload -> 'isError') = 'boolean'
            AND f_session_event_has_string(payload, 'sourceEventId', FALSE)
            AND ((payload ->> 'isError')::BOOLEAN = FALSE AND NOT payload ? 'errorCode'
                OR (payload ->> 'isError')::BOOLEAN = TRUE AND error_code IN (
                    'TOOL_CALL_DENIED', 'TOOL_ARGUMENTS_INVALID', 'TOOL_NOT_FOUND',
                    'TOOL_EXECUTION_TIMEOUT', 'TOOL_EXECUTION_FAILED'
                )), FALSE);
    ELSIF event_type = 'session.status_idle' THEN
        reason_value := payload ->> 'reason';
        error_code := payload ->> 'errorCode';
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['reason', 'sourceEventId'], ARRAY['errorCode', 'message'])
            AND reason_value IN ('done', 'failed', 'terminated', 'confirming')
            AND f_session_event_has_string(payload, 'sourceEventId', FALSE)
            AND (reason_value != 'failed' AND NOT payload ? 'errorCode' AND NOT payload ? 'message'
                OR reason_value = 'failed'
                    AND error_code IN (
                        'AGENT_EXECUTION_FAILED', 'MODEL_REQUEST_FAILED', 'EXECUTION_START_FAILED',
                        'EXECUTION_RESUME_FAILED', 'EVENT_PAYLOAD_TOO_LARGE'
                    )
                    AND f_session_event_has_string(payload, 'message', FALSE)), FALSE);
    ELSIF event_type = 'session.model_changed' THEN
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['previousModelId', 'modelId', 'reason'], ARRAY[]::TEXT[])
            AND f_session_event_has_string(payload, 'previousModelId', FALSE)
            AND f_session_event_has_string(payload, 'modelId', FALSE)
            AND payload ->> 'reason' IN ('requested', 'agentRefresh'), FALSE);
    ELSIF event_type = 'session.thinking_changed' THEN
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload, ARRAY['previousThinking', 'thinking', 'reason'], ARRAY[]::TEXT[])
            AND JSONB_TYPEOF(payload -> 'previousThinking') = 'boolean'
            AND JSONB_TYPEOF(payload -> 'thinking') = 'boolean'
            AND payload ->> 'reason' IN ('requested', 'modelCapability'), FALSE);
    ELSIF event_type = 'session.compacted' THEN
        reason_value := payload ->> 'reason';
        RETURN COALESCE(f_session_event_has_exact_keys(
                payload,
                ARRAY['reason', 'tokensBefore', 'estimatedTokensAfter'],
                ARRAY['sourceEventId'])
            AND reason_value IN ('manual', 'threshold', 'overflow')
            AND f_session_event_has_nonnegative_long(payload, 'tokensBefore')
            AND f_session_event_has_nonnegative_long(payload, 'estimatedTokensAfter')
            AND (reason_value = 'manual' AND NOT payload ? 'sourceEventId'
                OR reason_value IN ('threshold', 'overflow')
                    AND f_session_event_has_string(payload, 'sourceEventId', FALSE)), FALSE);
    END IF;
    RETURN FALSE;
END;
$$;

COMMENT ON FUNCTION f_validate_session_event_v2(VARCHAR, JSONB) IS '校验迁移事件的 v2 公共类型、精确字段和字段值';

CREATE OR REPLACE FUNCTION f_session_event_mapping_count_valid(entry_type VARCHAR, event_count INTEGER)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(CASE
        WHEN entry_type = 'assistant.message.completed' THEN event_count >= 1
        WHEN entry_type = 'assistant.thinking.completed' THEN event_count BETWEEN 0 AND 1
        WHEN entry_type IN (
            'user.message', 'user.interrupt', 'user.tool_confirmation',
            'tool.execution.started', 'tool.result', 'session.model.changed',
            'session.thinking.changed', 'session.compaction.completed', 'session.status.idle'
        ) THEN event_count = 1
        ELSE FALSE
    END, FALSE);
$$;

COMMENT ON FUNCTION f_session_event_mapping_count_valid(VARCHAR, INTEGER) IS '校验旧 Entry 审核映射所需的精确公共事件数量';

CREATE OR REPLACE FUNCTION f_session_event_mapping_type_valid(entry_type VARCHAR, event_type VARCHAR)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT COALESCE(CASE entry_type
        WHEN 'user.message' THEN event_type = 'user.message'
        WHEN 'user.interrupt' THEN event_type = 'user.interrupt'
        WHEN 'user.tool_confirmation' THEN event_type = 'user.tool_confirmation'
        WHEN 'assistant.message.completed' THEN event_type IN ('agent.message', 'agent.tool_call')
        WHEN 'assistant.thinking.completed' THEN event_type = 'agent.thinking'
        WHEN 'tool.execution.started' THEN event_type = 'agent.tool_call'
        WHEN 'tool.result' THEN event_type = 'agent.tool_result'
        WHEN 'session.model.changed' THEN event_type = 'session.model_changed'
        WHEN 'session.thinking.changed' THEN event_type = 'session.thinking_changed'
        WHEN 'session.compaction.completed' THEN event_type = 'session.compacted'
        WHEN 'session.status.idle' THEN event_type = 'session.status_idle'
        ELSE FALSE
    END, FALSE);
$$;

COMMENT ON FUNCTION f_session_event_mapping_type_valid(VARCHAR, VARCHAR) IS '校验旧 Entry 与审核公共事件类型的允许映射';

CREATE OR REPLACE FUNCTION f_session_event_migration_gaps()
RETURNS TABLE (
    session_id VARCHAR(128),
    anchor_entry_id VARCHAR(128),
    entry_type VARCHAR(64),
    gap_reason VARCHAR(64)
)
LANGUAGE SQL
STABLE
AS $$
WITH event_counts AS (
    SELECT session_id, anchor_entry_id, COUNT(1) AS event_count
    FROM t_session_events
    GROUP BY session_id, anchor_entry_id
), entry_gaps AS (
    SELECT entry.session_id, entry.id AS anchor_entry_id, entry.type AS entry_type,
           CASE
               WHEN entry.type NOT IN (
                   'user.message', 'user.interrupt', 'user.tool_confirmation',
                   'assistant.message.started', 'assistant.message.delta', 'assistant.message.completed',
                   'assistant.thinking.started', 'assistant.thinking.delta', 'assistant.thinking.completed',
                   'tool.execution.started', 'tool.execution.delta', 'tool.execution.completed', 'tool.result',
                   'session.model.changed', 'session.thinking.changed', 'session.compaction.started',
                   'session.compaction.completed', 'session.compaction.failed', 'session.status.idle',
                   'stream.end', 'stream.error', 'leaf', 'branch_summary', 'label'
               ) THEN 'UNKNOWN_ENTRY_TYPE'
               WHEN projection.anchor_entry_id IS NULL THEN 'MIGRATION_REVIEW_REQUIRED'
               WHEN projection.event_count != COALESCE(actual.event_count, 0) THEN 'EVENT_COUNT_MISMATCH'
               WHEN entry.type IN (
                   'user.message', 'user.interrupt', 'user.tool_confirmation',
                   'assistant.message.completed', 'tool.execution.started', 'tool.result',
                   'session.model.changed', 'session.thinking.changed', 'session.compaction.completed',
                   'session.status.idle'
               ) AND projection.event_count < 1 THEN 'PUBLIC_EVENT_MISSING'
               WHEN entry.type IN (
                   'assistant.message.started', 'assistant.message.delta',
                   'assistant.thinking.started', 'assistant.thinking.delta',
                   'tool.execution.delta', 'tool.execution.completed',
                   'session.compaction.started', 'session.compaction.failed',
                   'stream.end', 'stream.error', 'leaf', 'branch_summary', 'label'
               ) AND projection.event_count != 0 THEN 'PRIVATE_ENTRY_HAS_EVENT'
           END AS gap_reason
    FROM t_session_entries entry
    LEFT JOIN t_session_event_projection projection
      ON projection.session_id = entry.session_id
     AND projection.anchor_entry_id = entry.id
    LEFT JOIN event_counts actual
      ON actual.session_id = entry.session_id
     AND actual.anchor_entry_id = entry.id
), review_counts AS (
    SELECT review.session_id, review.anchor_entry_id, review.event_count,
           COUNT(event.event_id) AS actual_count,
           MIN(event.event_order) AS first_order,
           MAX(event.event_order) AS last_order
    FROM t_session_event_migration_review review
    LEFT JOIN t_session_event_migration_events event
      ON event.session_id = review.session_id
     AND event.anchor_entry_id = review.anchor_entry_id
    GROUP BY review.session_id, review.anchor_entry_id, review.event_count
), persisted_events AS (
    SELECT event.session_id, event.event_id, event.anchor_entry_id, event.type, event.payload
    FROM t_session_events event
), all_reviewed_events AS (
    SELECT session_id, event_id, anchor_entry_id, type, payload
    FROM persisted_events
    UNION ALL
    SELECT session_id, event_id, anchor_entry_id, type, payload
    FROM t_session_event_migration_events
)
SELECT session_id, anchor_entry_id, entry_type, gap_reason
FROM entry_gaps
WHERE gap_reason IS NOT NULL

UNION ALL

SELECT projection.session_id, projection.anchor_entry_id, NULL, 'ORPHAN_PROJECTION'
FROM t_session_event_projection projection
LEFT JOIN t_session_entries entry
  ON entry.session_id = projection.session_id
 AND entry.id = projection.anchor_entry_id
WHERE entry.id IS NULL

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'ORPHAN_PUBLIC_EVENT'
FROM t_session_events event
LEFT JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
WHERE entry.id IS NULL

UNION ALL

SELECT review.session_id, review.anchor_entry_id, entry.type, 'INVALID_REVIEW_COUNT'
FROM review_counts review
LEFT JOIN t_session_entries entry
  ON entry.session_id = review.session_id
 AND entry.id = review.anchor_entry_id
WHERE entry.id IS NULL
   OR review.event_count != review.actual_count
   OR (review.event_count > 0 AND (review.first_order != 1 OR review.last_order != review.event_count))
   OR f_session_event_mapping_count_valid(entry.type, review.event_count) IS NOT TRUE

UNION ALL

SELECT event.session_id, event.anchor_entry_id, entry.type, 'INVALID_REVIEWED_EVENT'
FROM t_session_event_migration_events event
LEFT JOIN t_session_event_migration_review review
  ON review.session_id = event.session_id
 AND review.anchor_entry_id = event.anchor_entry_id
LEFT JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
WHERE review.anchor_entry_id IS NULL
   OR entry.id IS NULL

UNION ALL

SELECT event.session_id, event.anchor_entry_id, entry.type, 'INVALID_PUBLIC_PAYLOAD'
FROM t_session_event_migration_events event
LEFT JOIN t_session_event_migration_review review
  ON review.session_id = event.session_id
 AND review.anchor_entry_id = event.anchor_entry_id
LEFT JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
WHERE review.anchor_entry_id IS NOT NULL
  AND entry.id IS NOT NULL
  AND f_validate_session_event_v2(event.type, event.payload) IS NOT TRUE

UNION ALL

SELECT event.session_id, event.anchor_entry_id, entry.type, 'INVALID_EVENT_MAPPING'
FROM t_session_event_migration_events event
JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
WHERE f_session_event_mapping_type_valid(entry.type, event.type) IS NOT TRUE

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'INVALID_PUBLIC_PAYLOAD'
FROM persisted_events event
WHERE f_validate_session_event_v2(event.type, event.payload) IS NOT TRUE

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'DUPLICATE_EVENT_ID'
FROM all_reviewed_events event
WHERE EXISTS (
    SELECT 1
    FROM all_reviewed_events duplicate
    WHERE duplicate.session_id = event.session_id
      AND duplicate.event_id = event.event_id
    GROUP BY duplicate.session_id, duplicate.event_id
    HAVING COUNT(1) > 1
)

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'SOURCE_EVENT_MISSING'
FROM all_reviewed_events event
WHERE event.type IN (
    'agent.message', 'agent.thinking', 'agent.tool_call', 'agent.tool_result', 'session.status_idle'
)
AND NOT EXISTS (
    SELECT 1
    FROM all_reviewed_events source
    WHERE source.session_id = event.session_id
      AND source.event_id = event.payload ->> 'sourceEventId'
      AND source.type = 'user.message'
)

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'TARGET_EVENT_MISSING'
FROM all_reviewed_events event
WHERE event.type = 'user.interrupt'
AND NOT EXISTS (
    SELECT 1
    FROM all_reviewed_events target
    WHERE target.session_id = event.session_id
      AND target.event_id = event.payload ->> 'targetEventId'
      AND target.type = 'user.message'
)

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'TOOL_CALL_MISSING'
FROM all_reviewed_events event
WHERE event.type IN ('user.tool_confirmation', 'agent.tool_result')
AND NOT EXISTS (
    SELECT 1
    FROM all_reviewed_events tool_call
    WHERE tool_call.session_id = event.session_id
      AND tool_call.type = 'agent.tool_call'
      AND tool_call.payload ->> 'toolCallId' = event.payload ->> 'toolCallId'
)

UNION ALL

SELECT event.session_id, event.anchor_entry_id, event.type, 'COMPACTION_SOURCE_INVALID'
FROM all_reviewed_events event
WHERE event.type = 'session.compacted'
  AND (
      COALESCE(event.payload ->> 'reason', '') NOT IN ('manual', 'threshold', 'overflow')
      OR (event.payload ->> 'reason' = 'manual' AND event.payload ? 'sourceEventId')
      OR (
          event.payload ->> 'reason' IN ('threshold', 'overflow')
          AND NOT EXISTS (
              SELECT 1
              FROM all_reviewed_events source
              WHERE source.session_id = event.session_id
                AND source.event_id = event.payload ->> 'sourceEventId'
                AND source.type = 'user.message'
          )
      )
  )
$$;

COMMENT ON FUNCTION f_session_event_migration_gaps() IS '返回不含公共载荷和审核正文的 Events v2 迁移固定缺口';

COMMIT;
