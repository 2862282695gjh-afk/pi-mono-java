\set ON_ERROR_STOP on

-- Run only in a disposable database. The script owns and recreates its isolated schema.
DROP SCHEMA IF EXISTS campusclaw_events_v2_payload_test CASCADE;
CREATE SCHEMA campusclaw_events_v2_payload_test;
SET search_path TO campusclaw_events_v2_payload_test;

\ir ../../install/session_schema.sql
\ir ../V1_to_V2__schema.sql

CREATE TEMP TABLE t_payload_validation_cases (
    case_id    VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload    JSONB       NOT NULL,
    expected   BOOLEAN     NOT NULL
);

INSERT INTO t_payload_validation_cases VALUES
    ('valid_user_message', 'user.message',
        '{"content":[{"type":"text","text":"hello"},{"type":"file","fileId":"0123456789abcdef0123456789abcdef"}]}', TRUE),
    ('valid_interrupt', 'user.interrupt', '{"targetEventId":"event_user"}', TRUE),
    ('valid_java_nonblank_nbsp', 'user.interrupt', '{"targetEventId":"\u00a0"}', TRUE),
    ('valid_confirmation_allow', 'user.tool_confirmation',
        '{"toolCallId":"call_1","result":"allow"}', TRUE),
    ('valid_confirmation_deny', 'user.tool_confirmation',
        '{"toolCallId":"call_1","result":"deny","denyMessage":"no"}', TRUE),
    ('valid_agent_message', 'agent.message',
        '{"phase":"completed","content":"","sourceEventId":"event_user",'
        '"usage":{"input":1,"output":2,"cacheRead":3,"cacheWrite":4,"totalTokens":10,'
        '"cost":{"input":0.1,"output":0.2,"cacheRead":0,"cacheWrite":0,"total":0.3}}}', TRUE),
    ('valid_thinking', 'agent.thinking',
        '{"phase":"completed","content":"summary","sourceEventId":"event_user"}', TRUE),
    ('valid_tool_call', 'agent.tool_call',
        '{"toolCallId":"call_1","toolName":"CallMateTool",'
        '"arguments":{"tool":"inspect","args":{}},'
        '"requiresConfirmation":true,"sourceEventId":"event_user"}', TRUE),
    ('valid_generic_tool_call', 'agent.tool_call',
        '{"toolCallId":"call_2","toolName":"Read","arguments":{},'
        '"requiresConfirmation":true,"sourceEventId":"event_user"}', TRUE),
    ('valid_tool_result', 'agent.tool_result',
        '{"toolCallId":"call_1","content":[{"type":"text","text":""}],'
        '"isError":false,"sourceEventId":"event_user"}', TRUE),
    ('valid_tool_failure', 'agent.tool_result',
        '{"toolCallId":"call_1","content":[{"type":"text","text":"failed"}],'
        '"isError":true,"errorCode":"TOOL_EXECUTION_FAILED","sourceEventId":"event_user"}', TRUE),
    ('valid_idle_done', 'session.status_idle',
        '{"reason":"done","sourceEventId":"event_user"}', TRUE),
    ('valid_idle_failed', 'session.status_idle',
        '{"reason":"failed","sourceEventId":"event_user",'
        '"errorCode":"MODEL_REQUEST_FAILED","message":"retry later"}', TRUE),
    ('valid_model', 'session.model_changed',
        '{"previousModelId":"model-a","modelId":"model-b","reason":"requested"}', TRUE),
    ('valid_thinking_config', 'session.thinking_changed',
        '{"previousThinking":false,"thinking":true,"reason":"modelCapability"}', TRUE),
    ('valid_manual_compaction', 'session.compacted',
        '{"reason":"manual","tokensBefore":100,"estimatedTokensAfter":50}', TRUE),
    ('valid_auto_compaction', 'session.compacted',
        '{"reason":"threshold","tokensBefore":100,"estimatedTokensAfter":50,"sourceEventId":"event_user"}', TRUE),
    ('invalid_empty_object', 'user.message', '{}', FALSE),
    ('invalid_json_null', 'user.message', 'null', FALSE),
    ('invalid_java_ascii_blank', 'user.interrupt', '{"targetEventId":"\t\n"}', FALSE),
    ('invalid_java_unicode_blank', 'user.interrupt', '{"targetEventId":"\u2003"}', FALSE),
    ('invalid_unknown_type', 'private.event', '{}', FALSE),
    ('invalid_extra_field', 'agent.message',
        '{"phase":"completed","content":"safe","sourceEventId":"event_user","privateTrace":"secret"}', FALSE),
    ('invalid_delta_phase', 'agent.message',
        '{"phase":"delta","content":"partial","sourceEventId":"event_user"}', FALSE),
    ('invalid_usage_negative', 'agent.message',
        '{"phase":"completed","content":"safe","sourceEventId":"event_user",'
        '"usage":{"input":-1,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}}', FALSE),
    ('invalid_usage_decimal', 'agent.message',
        '{"phase":"completed","content":"safe","sourceEventId":"event_user",'
        '"usage":{"input":1.0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}}', FALSE),
    ('invalid_usage_overflow', 'agent.message',
        '{"phase":"completed","content":"safe","sourceEventId":"event_user",'
        '"usage":{"input":9223372036854775808,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}}', FALSE),
    ('invalid_cost_negative', 'agent.message',
        '{"phase":"completed","content":"safe","sourceEventId":"event_user",'
        '"usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,'
        '"cost":{"input":-0.1,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}}', FALSE),
    ('invalid_tool_arguments', 'agent.tool_call',
        '{"toolCallId":"call_1","toolName":"CallMateTool","arguments":"{}",'
        '"requiresConfirmation":true,"sourceEventId":"event_user"}', FALSE),
    ('invalid_mate_wrapper', 'agent.tool_call',
        '{"toolCallId":"call_1","toolName":"CallMateTool","arguments":{},'
        '"requiresConfirmation":true,"sourceEventId":"event_user"}', FALSE),
    ('invalid_tool_confirmation_type', 'agent.tool_call',
        '{"toolCallId":"call_1","toolName":"CallMateTool","arguments":{},'
        '"requiresConfirmation":"true","sourceEventId":"event_user"}', FALSE),
    ('invalid_tool_success_error', 'agent.tool_result',
        '{"toolCallId":"call_1","content":[{"type":"text","text":"ok"}],'
        '"isError":false,"errorCode":"TOOL_EXECUTION_FAILED","sourceEventId":"event_user"}', FALSE),
    ('invalid_tool_failure_code', 'agent.tool_result',
        '{"toolCallId":"call_1","content":[{"type":"text","text":"failed"}],'
        '"isError":true,"errorCode":"RAW_PROVIDER_ERROR","sourceEventId":"event_user"}', FALSE),
    ('invalid_idle_fields', 'session.status_idle',
        '{"reason":"done","sourceEventId":"event_user","message":"unexpected"}', FALSE),
    ('invalid_manual_source', 'session.compacted',
        '{"reason":"manual","tokensBefore":100,"estimatedTokensAfter":50,"sourceEventId":"event_user"}', FALSE),
    ('invalid_file_id', 'user.message',
        '{"content":[{"type":"file","fileId":"not-a-file-id"}]}', FALSE),
    ('invalid_numeric_file_id', 'user.message',
        '{"content":[{"type":"file","fileId":12345678901234567890123456789012}]}', FALSE),
    ('invalid_blank_message', 'user.message',
        '{"content":[{"type":"text","text":"\t\n"}]}', FALSE),
    ('invalid_five_files', 'user.message',
        '{"content":[{"type":"file","fileId":"00000000000000000000000000000001"},'
        '{"type":"file","fileId":"00000000000000000000000000000002"},'
        '{"type":"file","fileId":"00000000000000000000000000000003"},'
        '{"type":"file","fileId":"00000000000000000000000000000004"},'
        '{"type":"file","fileId":"00000000000000000000000000000005"}]}', FALSE),
    ('invalid_duplicate_file', 'user.message',
        '{"content":[{"type":"file","fileId":"0123456789abcdef0123456789abcdef"},'
        '{"type":"file","fileId":"0123456789abcdef0123456789abcdef"}]}', FALSE),
    ('invalid_text_position', 'user.message',
        '{"content":[{"type":"file","fileId":"0123456789abcdef0123456789abcdef"},'
        '{"type":"text","text":"late"}]}', FALSE);

INSERT INTO t_payload_validation_cases VALUES (
    'invalid_utf16_message_length',
    'user.message',
    JSON_BUILD_OBJECT(
        'content',
        JSON_BUILD_ARRAY(JSON_BUILD_OBJECT(
            'type', 'text',
            'text', REPEAT(JSONB_EXTRACT_PATH_TEXT('{"value":"\uD83D\uDE00"}'::JSONB, 'value'), 131073)
        ))
    )::JSONB,
    FALSE
), (
    'invalid_utf16_deny_length',
    'user.tool_confirmation',
    JSON_BUILD_OBJECT(
        'toolCallId', 'call_1',
        'result', 'deny',
        'denyMessage', REPEAT(JSONB_EXTRACT_PATH_TEXT('{"value":"\uD83D\uDE00"}'::JSONB, 'value'), 2049)
    )::JSONB,
    FALSE
);

DO $$
DECLARE
    mismatch_count INTEGER;
BEGIN
    SELECT COUNT(1)
    INTO mismatch_count
    FROM t_payload_validation_cases test_case
    WHERE f_validate_session_event_v2(test_case.event_type, test_case.payload)
        IS DISTINCT FROM test_case.expected;

    IF mismatch_count != 0 THEN
        RAISE EXCEPTION 'Events v2 payload validation regression failed: % cases', mismatch_count;
    END IF;

    IF f_session_event_utf16_length(
            JSONB_EXTRACT_PATH_TEXT('{"value":"\uD83D\uDE00"}'::JSONB, 'value')) != 2 THEN
        RAISE EXCEPTION 'Events v2 UTF-16 length regression failed';
    END IF;
END;
$$;

DROP SCHEMA campusclaw_events_v2_payload_test CASCADE;
