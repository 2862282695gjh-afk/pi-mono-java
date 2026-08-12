-- Replace ${SESSION_SCHEMA} and ${SESSION_RUNTIME_ROLE} through the database release platform.
-- Execute after V1.0.0__session_schema.sql while connected to the target Session schema.

BEGIN;

GRANT USAGE ON SCHEMA ${SESSION_SCHEMA} TO ${SESSION_RUNTIME_ROLE};

GRANT SELECT, INSERT, UPDATE, DELETE
    ON t_sessions,
       t_session_entries,
       t_session_sequences,
       t_session_materialized
    TO ${SESSION_RUNTIME_ROLE};

COMMIT;
