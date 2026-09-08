\set ON_ERROR_STOP on

-- Run only in a disposable database. The script owns and recreates its isolated schema.
DROP SCHEMA IF EXISTS campusclaw_events_v2_migration_test CASCADE;
CREATE SCHEMA campusclaw_events_v2_migration_test;
SET search_path TO campusclaw_events_v2_migration_test;

\ir ../../install/session_schema.sql
\ir ../V1_to_V2__schema.sql
\ir ../V1_to_V2__schema.sql

INSERT INTO t_sessions (
    id, agent_id, model_id, state, thinking, resource_version, created_at, updated_at, cwd, active_leaf_id
) VALUES
    ('session_good', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_good_thinking_public'),
    ('session_half', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_half_assistant'),
    ('session_unknown', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_unknown'),
    ('session_skill', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_skill'),
    ('session_source', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_source'),
    ('session_cross', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_cross'),
    ('session_invalid', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_invalid'),
    ('session_mapping', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_mapping'),
    ('session_zero', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_zero'),
    ('session_existing_count', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_existing_count_model'),
    ('session_existing_mapping', 'agent', 'model', 'idle', TRUE, 1, '2026-09-08T01:00:00Z', '2026-09-08T01:00:00Z', '/tmp', 'entry_existing_mapping_model');

INSERT INTO t_session_sequences (session_id, next_seq) VALUES
    ('session_good', 100),
    ('session_half', 200),
    ('session_unknown', 300),
    ('session_skill', 400),
    ('session_source', 500),
    ('session_cross', 600),
    ('session_invalid', 700),
    ('session_mapping', 750),
    ('session_zero', 800),
    ('session_existing_count', 850),
    ('session_existing_mapping', 900);

INSERT INTO t_session_entries (session_id, id, entry_seq, parent_id, type, timestamp, payload) VALUES
    ('session_good', 'entry_good_user', 1, NULL, 'user.message', '2026-09-08T01:00:01.111Z', '{"message":"ordinary"}'),
    ('session_good', 'entry_good_thinking_private', 2, 'entry_good_user', 'assistant.thinking.completed', '2026-09-08T01:00:02.222Z', '{"thinking":"private"}'),
    ('session_good', 'entry_good_thinking_public', 3, 'entry_good_thinking_private', 'assistant.thinking.completed', '2026-09-08T01:00:03.333Z', '{"thinking":"reviewed-summary"}'),
    ('session_half', 'entry_half_user', 1, NULL, 'user.message', '2026-09-08T01:01:01Z', '{"message":"ordinary"}'),
    ('session_half', 'entry_half_assistant', 2, 'entry_half_user', 'assistant.message.completed', '2026-09-08T01:01:02Z', '{"message":"answer"}'),
    ('session_unknown', 'entry_unknown', 1, NULL, 'provider.private', '2026-09-08T01:02:01Z', '{}'),
    ('session_skill', 'entry_skill', 1, NULL, 'user.message', '2026-09-08T01:03:01Z', '{"message":"expanded-skill-body-without-original-receipt"}'),
    ('session_source', 'entry_source', 1, NULL, 'user.message', '2026-09-08T01:04:01Z', '{"message":"ordinary"}'),
    ('session_cross', 'entry_cross', 1, NULL, 'assistant.message.completed', '2026-09-08T01:05:01Z', '{"message":"answer"}'),
    ('session_invalid', 'entry_invalid', 1, NULL, 'user.message', '2026-09-08T01:06:01Z', '{"message":"ordinary"}'),
    ('session_mapping', 'entry_mapping', 1, NULL, 'user.message', '2026-09-08T01:06:31Z', '{"message":"ordinary"}'),
    ('session_zero', 'entry_zero', 1, NULL, 'user.message', '2026-09-08T01:07:01Z', '{"message":"ordinary"}'),
    ('session_existing_count', 'entry_existing_count_user', 1, NULL, 'user.message', '2026-09-08T01:08:01Z', '{"message":"ordinary"}'),
    ('session_existing_count', 'entry_existing_count_model', 2, 'entry_existing_count_user', 'session.model.changed', '2026-09-08T01:08:02Z',
        '{"previousModelId":"model","modelId":"next","reason":"requested"}'),
    ('session_existing_mapping', 'entry_existing_mapping_user', 1, NULL, 'user.message', '2026-09-08T01:09:01Z', '{"message":"ordinary"}'),
    ('session_existing_mapping', 'entry_existing_mapping_model', 2, 'entry_existing_mapping_user', 'session.model.changed', '2026-09-08T01:09:02Z',
        '{"previousModelId":"model","modelId":"next","reason":"requested"}');

INSERT INTO t_session_event_migration_review (
    session_id, anchor_entry_id, event_count, mapping_reason
) VALUES
    ('session_good', 'entry_good_user', 1, 'reviewed ordinary receipt'),
    ('session_good', 'entry_good_thinking_private', 0, 'reviewed private provider thinking'),
    ('session_good', 'entry_good_thinking_public', 1, 'reviewed public provider summary'),
    ('session_half', 'entry_half_user', 1, 'reviewed ordinary receipt'),
    ('session_half', 'entry_half_assistant', 2, 'reviewed two complete public events'),
    ('session_source', 'entry_source', 1, 'reviewed ordinary receipt'),
    ('session_cross', 'entry_cross', 1, 'reviewed response association'),
    ('session_invalid', 'entry_invalid', 1, 'reviewed malformed receipt fixture'),
    ('session_mapping', 'entry_mapping', 1, 'reviewed incompatible type fixture'),
    ('session_zero', 'entry_zero', 0, 'invalid public zero fixture');

INSERT INTO t_session_event_migration_events (
    session_id, anchor_entry_id, event_order, event_id, type, created_at, payload
) VALUES
    ('session_good', 'entry_good_user', 1, 'event_good_user', 'user.message', '2026-09-08T01:00:01.111Z',
        '{"content":[{"type":"text","text":"ordinary"}]}'),
    ('session_good', 'entry_good_thinking_public', 1, 'event_good_thinking', 'agent.thinking', '2026-09-08T01:00:03.333Z',
        '{"phase":"completed","content":"safe summary","sourceEventId":"event_good_user"}'),
    ('session_half', 'entry_half_user', 1, 'event_half_user', 'user.message', '2026-09-08T01:01:01Z',
        '{"content":[{"type":"text","text":"ordinary"}]}'),
    ('session_half', 'entry_half_assistant', 1, 'event_half_answer', 'agent.message', '2026-09-08T01:01:02Z',
        '{"phase":"completed","content":"answer","sourceEventId":"event_half_user"}'),
    ('session_source', 'entry_source', 1, 'event_source', 'user.message', '2026-09-08T01:04:01Z',
        '{"content":[{"type":"text","text":"ordinary"}]}'),
    ('session_cross', 'entry_cross', 1, 'event_cross', 'agent.message', '2026-09-08T01:05:01Z',
        '{"phase":"completed","content":"answer","sourceEventId":"event_source"}'),
    ('session_invalid', 'entry_invalid', 1, 'event_invalid', 'user.message', '2026-09-08T01:06:01Z', '{}');
INSERT INTO t_session_event_migration_events (
    session_id, anchor_entry_id, event_order, event_id, type, created_at, payload
) VALUES (
    'session_mapping', 'entry_mapping', 1, 'event_mapping', 'agent.message', '2026-09-08T01:06:31Z',
    '{"phase":"completed","content":"wrong type","sourceEventId":"event_mapping"}'
);

INSERT INTO t_session_events (
    session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
) VALUES
    ('session_existing_count', 'event_existing_count_1', 10, 'entry_existing_count_user',
        'user.message', '2026-09-08T01:08:01Z', '{"content":[{"type":"text","text":"one"}]}'),
    ('session_existing_count', 'event_existing_count_2', 11, 'entry_existing_count_user',
        'user.message', '2026-09-08T01:08:01Z', '{"content":[{"type":"text","text":"two"}]}'),
    ('session_existing_mapping', 'event_existing_mapping', 10, 'entry_existing_mapping_user',
        'session.model_changed', '2026-09-08T01:09:01Z',
        '{"previousModelId":"model","modelId":"next","reason":"requested"}');

INSERT INTO t_session_event_projection (session_id, anchor_entry_id, event_count, mapping_source) VALUES
    ('session_existing_count', 'entry_existing_count_user', 2, 'migration'),
    ('session_existing_mapping', 'entry_existing_mapping_user', 1, 'migration');

\ir ../V1_to_V2__data.sql

DO $$
BEGIN
    IF (SELECT COUNT(1) FROM t_session_events WHERE session_id = 'session_good') != 2
            OR (SELECT COUNT(1) FROM t_session_event_projection WHERE session_id = 'session_good') != 3
            OR NOT EXISTS (
                SELECT 1 FROM t_session_event_projection
                WHERE session_id = 'session_good'
                  AND anchor_entry_id = 'entry_good_thinking_private'
                  AND event_count = 0
            )
            OR NOT EXISTS (
                SELECT 1 FROM t_session_events
                WHERE session_id = 'session_good'
                  AND event_id = 'event_good_thinking'
                  AND type = 'agent.thinking'
            ) THEN
        RAISE EXCEPTION 'Events v2 thinking migration regression failed';
    END IF;

    IF (SELECT next_seq FROM t_session_sequences WHERE session_id = 'session_good') != 102
            OR (SELECT next_seq FROM t_session_sequences WHERE session_id = 'session_source') != 501 THEN
        RAISE EXCEPTION 'Events v2 sequence migration regression failed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM t_session_events
        WHERE session_id IN ('session_half', 'session_unknown', 'session_skill', 'session_cross',
            'session_invalid', 'session_mapping', 'session_zero')
    ) OR EXISTS (
        SELECT 1 FROM t_session_event_projection
        WHERE session_id IN ('session_half', 'session_unknown', 'session_skill', 'session_cross',
            'session_invalid', 'session_mapping', 'session_zero')
    ) THEN
        RAISE EXCEPTION 'Events v2 fail-closed migration regression failed';
    END IF;

    IF (SELECT COUNT(1) FROM t_session_events WHERE session_id = 'session_existing_count') != 2
            OR (SELECT COUNT(1) FROM t_session_event_projection
                WHERE session_id = 'session_existing_count') != 1
            OR (SELECT COUNT(1) FROM t_session_events WHERE session_id = 'session_existing_mapping') != 1
            OR (SELECT COUNT(1) FROM t_session_event_projection
                WHERE session_id = 'session_existing_mapping') != 1 THEN
        RAISE EXCEPTION 'Events v2 existing mapping fail-closed regression failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_unknown' AND gap_reason = 'UNKNOWN_ENTRY_TYPE'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_skill' AND gap_reason = 'MIGRATION_REVIEW_REQUIRED'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_cross' AND gap_reason = 'SOURCE_EVENT_MISSING'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_invalid' AND gap_reason = 'INVALID_PUBLIC_PAYLOAD'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_mapping' AND gap_reason = 'INVALID_EVENT_MAPPING'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_zero' AND gap_reason = 'INVALID_REVIEW_COUNT'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_existing_count' AND gap_reason = 'INVALID_MAPPING_COUNT'
    ) OR NOT EXISTS (
        SELECT 1 FROM f_session_event_migration_gaps()
        WHERE session_id = 'session_existing_mapping' AND gap_reason = 'INVALID_EVENT_MAPPING'
    ) THEN
        RAISE EXCEPTION 'Events v2 fixed migration gap regression failed';
    END IF;
END;
$$;

CREATE TEMP TABLE t_stable_migration_snapshot AS
SELECT session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
FROM t_session_events
WHERE session_id IN ('session_good', 'session_source');

CREATE TEMP TABLE t_stable_sequence_snapshot AS
SELECT session_id, next_seq
FROM t_session_sequences
WHERE session_id IN ('session_good', 'session_source');

\ir ../V1_to_V2__data.sql

DO $$
BEGIN
    IF EXISTS (
        (SELECT * FROM t_stable_migration_snapshot EXCEPT
         SELECT session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
         FROM t_session_events WHERE session_id IN ('session_good', 'session_source'))
        UNION ALL
        (SELECT session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
         FROM t_session_events WHERE session_id IN ('session_good', 'session_source') EXCEPT
         SELECT * FROM t_stable_migration_snapshot)
    ) OR EXISTS (
        (SELECT * FROM t_stable_sequence_snapshot EXCEPT
         SELECT session_id, next_seq FROM t_session_sequences
         WHERE session_id IN ('session_good', 'session_source'))
        UNION ALL
        (SELECT session_id, next_seq FROM t_session_sequences
         WHERE session_id IN ('session_good', 'session_source') EXCEPT
         SELECT * FROM t_stable_sequence_snapshot)
    ) THEN
        RAISE EXCEPTION 'Events v2 restart stability regression failed';
    END IF;
END;
$$;

INSERT INTO t_session_event_migration_events (
    session_id, anchor_entry_id, event_order, event_id, type, created_at, payload
) VALUES (
    'session_half', 'entry_half_assistant', 2, 'event_half_tool', 'agent.tool_call',
    '2026-09-08T01:01:02.500Z',
    '{"toolCallId":"call_half","toolName":"Read","arguments":{},'
    '"requiresConfirmation":false,"sourceEventId":"event_half_user"}'
);

\ir ../V1_to_V2__data.sql

DO $$
BEGIN
    IF (SELECT COUNT(1) FROM t_session_events WHERE session_id = 'session_half') != 3
            OR (SELECT COUNT(1) FROM t_session_event_projection WHERE session_id = 'session_half') != 2
            OR (SELECT next_seq FROM t_session_sequences WHERE session_id = 'session_half') != 203
            OR EXISTS (
                SELECT 1 FROM f_session_event_migration_gaps()
                WHERE session_id IN ('session_good', 'session_source', 'session_half')
            ) THEN
        RAISE EXCEPTION 'Events v2 exact one-to-many migration regression failed';
    END IF;
END;
$$;

\ir ../V1_to_V2__verify.sql

DROP SCHEMA campusclaw_events_v2_migration_test CASCADE;
