-- Success returns no rows.

SELECT id
FROM t_sessions
WHERE agent_id IS NULL
   OR owner_id IS NULL
   OR bundle_revision IS NULL
   OR model_id IS NULL
   OR state NOT IN ('idle', 'running')
   OR resource_version <= 0
   OR updated_at IS NULL;

SELECT session_id
FROM t_session_tombstone
WHERE session_id IS NULL OR deleted_at IS NULL;

SELECT session_id
FROM t_session_cleanup_task
WHERE state NOT IN ('PENDING', 'RUNNING', 'RETRY')
   OR attempt_count < 0
   OR created_at IS NULL
   OR updated_at IS NULL;
