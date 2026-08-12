-- Run with the target Session schema as current_schema after installation.
-- Every query must return zero rows. Any returned row is a release validation failure.

-- Unexpected or missing Session tables.
WITH expected(table_name) AS (
    VALUES
        ('t_sessions'),
        ('t_session_entries'),
        ('t_session_sequences'),
        ('t_session_materialized')
), actual AS (
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = CURRENT_SCHEMA
      AND table_type = 'BASE TABLE'
)
SELECT 'table_set_mismatch' AS failure, COALESCE(expected.table_name, actual.table_name) AS object_name
FROM expected
FULL JOIN actual USING (table_name)
WHERE expected.table_name IS NULL OR actual.table_name IS NULL;

-- Missing, unexpected, or incorrectly typed columns. The target has exactly 17 columns.
WITH expected(table_name, column_name, data_type, character_maximum_length) AS (
    VALUES
        ('t_sessions', 'id', 'character varying', 128::BIGINT),
        ('t_sessions', 'created_at', 'timestamp with time zone', NULL::BIGINT),
        ('t_sessions', 'cwd', 'character varying', 512::BIGINT),
        ('t_sessions', 'parent_session_id', 'character varying', 128::BIGINT),
        ('t_sessions', 'metadata', 'jsonb', NULL::BIGINT),
        ('t_sessions', 'active_leaf_id', 'character varying', 128::BIGINT),
        ('t_session_entries', 'session_id', 'character varying', 128::BIGINT),
        ('t_session_entries', 'id', 'character varying', 128::BIGINT),
        ('t_session_entries', 'entry_seq', 'bigint', NULL::BIGINT),
        ('t_session_entries', 'parent_id', 'character varying', 128::BIGINT),
        ('t_session_entries', 'type', 'character varying', 64::BIGINT),
        ('t_session_entries', 'timestamp', 'timestamp with time zone', NULL::BIGINT),
        ('t_session_entries', 'payload', 'jsonb', NULL::BIGINT),
        ('t_session_sequences', 'session_id', 'character varying', 128::BIGINT),
        ('t_session_sequences', 'next_seq', 'bigint', NULL::BIGINT),
        ('t_session_materialized', 'session_id', 'character varying', 128::BIGINT),
        ('t_session_materialized', 'payload', 'jsonb', NULL::BIGINT)
), actual AS (
    SELECT table_name, column_name, data_type, character_maximum_length
    FROM information_schema.columns
    WHERE table_schema = CURRENT_SCHEMA
      AND table_name LIKE 't_session%'
)
SELECT
    'column_mismatch' AS failure,
    COALESCE(expected.table_name, actual.table_name) || '.'
        || COALESCE(expected.column_name, actual.column_name) AS object_name
FROM expected
FULL JOIN actual USING (table_name, column_name)
WHERE expected.column_name IS NULL
   OR actual.column_name IS NULL
   OR expected.data_type <> actual.data_type
   OR expected.character_maximum_length IS DISTINCT FROM actual.character_maximum_length;

-- Every target column must have a non-empty database comment.
SELECT 'missing_column_comment' AS failure, c.relname || '.' || a.attname AS object_name
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
LEFT JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum
WHERE n.nspname = CURRENT_SCHEMA
  AND c.relname IN ('t_sessions', 't_session_entries', 't_session_sequences', 't_session_materialized')
  AND COALESCE(d.description, '') = '';

-- Required non-primary indexes must exist.
WITH expected(index_name) AS (
    VALUES
        ('idx_t_sessions_created_at'),
        ('idx_t_sessions_cwd'),
        ('idx_t_sessions_parent'),
        ('idx_t_session_entries_session_seq'),
        ('idx_t_session_entries_session_parent'),
        ('idx_t_session_entries_session_type')
)
SELECT 'missing_index' AS failure, expected.index_name AS object_name
FROM expected
LEFT JOIN pg_indexes actual
    ON actual.schemaname = CURRENT_SCHEMA AND actual.indexname = expected.index_name
WHERE actual.indexname IS NULL;
