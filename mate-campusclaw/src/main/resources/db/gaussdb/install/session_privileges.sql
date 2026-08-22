-- Replace ${SESSION_SCHEMA} and ${SESSION_RUNTIME_ROLE} through the database release platform.
-- Execute after session_schema.sql and session_initial_data.sql while connected to the target Session schema.

BEGIN;

GRANT USAGE ON SCHEMA ${SESSION_SCHEMA} TO ${SESSION_RUNTIME_ROLE};

GRANT SELECT, INSERT, UPDATE, DELETE
    ON t_sessions,
       t_session_entries,
       t_session_sequences,
       t_session_materialized,
       t_session_tombstone,
       t_session_cleanup_task
    TO ${SESSION_RUNTIME_ROLE};

COMMIT;
