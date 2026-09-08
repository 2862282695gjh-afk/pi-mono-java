-- CampusClaw Events v2 restart-safe migration.
-- Run only after all Session writes are stopped and reviewed rows are staged.

BEGIN;

LOCK TABLE t_sessions IN SHARE MODE;
LOCK TABLE t_session_sequences IN SHARE MODE;
LOCK TABLE t_session_entries IN SHARE MODE;
LOCK TABLE t_session_events IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE t_session_event_projection IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE t_session_event_migration_review IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE t_session_event_migration_events IN SHARE ROW EXCLUSIVE MODE;

DROP TABLE IF EXISTS tmp_session_new_events;
DROP TABLE IF EXISTS tmp_session_event_migration_sessions;
DROP TABLE IF EXISTS tmp_session_event_migration_issues;
DROP TABLE IF EXISTS tmp_session_all_public_events;
DROP TABLE IF EXISTS tmp_session_event_candidates;
DROP TABLE IF EXISTS tmp_session_event_decisions;

CREATE TEMP TABLE tmp_session_event_decisions (
    session_id      VARCHAR(128) NOT NULL,
    anchor_entry_id VARCHAR(128) NOT NULL,
    event_count     INTEGER      NOT NULL,
    decision_source VARCHAR(32)  NOT NULL,
    PRIMARY KEY (session_id, anchor_entry_id)
);

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT session_id, anchor_entry_id, event_count, 'existing'
FROM t_session_event_projection;

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT entry.session_id, entry.id, 0, 'auto_private'
FROM t_session_entries entry
WHERE entry.type IN (
    'assistant.message.started', 'assistant.message.delta',
    'assistant.thinking.started', 'assistant.thinking.delta',
    'tool.execution.delta', 'tool.execution.completed',
    'session.compaction.started', 'session.compaction.failed',
    'stream.end', 'stream.error', 'leaf', 'branch_summary', 'label'
)
AND NOT EXISTS (
    SELECT 1
    FROM tmp_session_event_decisions decision
    WHERE decision.session_id = entry.session_id
      AND decision.anchor_entry_id = entry.id
);

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT entry.session_id, entry.id, 1, 'auto_model'
FROM t_session_entries entry
WHERE entry.type = 'session.model.changed'
  AND JSONB_TYPEOF(entry.payload -> 'previousModelId') = 'string'
  AND LENGTH(BTRIM(entry.payload ->> 'previousModelId')) > 0
  AND JSONB_TYPEOF(entry.payload -> 'modelId') = 'string'
  AND LENGTH(BTRIM(entry.payload ->> 'modelId')) > 0
  AND entry.payload ->> 'reason' IN ('requested', 'agentRefresh')
  AND NOT EXISTS (
      SELECT 1
      FROM tmp_session_event_decisions decision
      WHERE decision.session_id = entry.session_id
        AND decision.anchor_entry_id = entry.id
  );

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT entry.session_id, entry.id, 1, 'auto_thinking'
FROM t_session_entries entry
WHERE entry.type = 'session.thinking.changed'
  AND JSONB_TYPEOF(entry.payload -> 'previousThinking') = 'boolean'
  AND JSONB_TYPEOF(entry.payload -> 'thinking') = 'boolean'
  AND entry.payload ->> 'reason' IN ('requested', 'modelCapability')
  AND NOT EXISTS (
      SELECT 1
      FROM tmp_session_event_decisions decision
      WHERE decision.session_id = entry.session_id
        AND decision.anchor_entry_id = entry.id
  );

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT entry.session_id, entry.id, 1, 'auto_manual_compaction'
FROM t_session_entries entry
WHERE entry.type = 'session.compaction.completed'
  AND entry.payload ->> 'reason' = 'manual'
  AND JSONB_TYPEOF(entry.payload -> 'tokensBefore') = 'number'
  AND JSONB_TYPEOF(entry.payload -> 'estimatedTokensAfter') = 'number'
  AND CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'tokensBefore') = 'number'
      THEN (entry.payload ->> 'tokensBefore')::NUMERIC
      ELSE -1
  END BETWEEN 0 AND 9223372036854775807
  AND CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'estimatedTokensAfter') = 'number'
      THEN (entry.payload ->> 'estimatedTokensAfter')::NUMERIC
      ELSE -1
  END BETWEEN 0 AND 9223372036854775807
  AND CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'tokensBefore') = 'number'
      THEN (entry.payload ->> 'tokensBefore')::NUMERIC
      ELSE -1
  END = TRUNC(CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'tokensBefore') = 'number'
      THEN (entry.payload ->> 'tokensBefore')::NUMERIC
      ELSE -1
  END)
  AND CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'estimatedTokensAfter') = 'number'
      THEN (entry.payload ->> 'estimatedTokensAfter')::NUMERIC
      ELSE -1
  END = TRUNC(CASE
      WHEN JSONB_TYPEOF(entry.payload -> 'estimatedTokensAfter') = 'number'
      THEN (entry.payload ->> 'estimatedTokensAfter')::NUMERIC
      ELSE -1
  END)
  AND NOT EXISTS (
      SELECT 1
      FROM tmp_session_event_decisions decision
      WHERE decision.session_id = entry.session_id
        AND decision.anchor_entry_id = entry.id
  );

INSERT INTO tmp_session_event_decisions (session_id, anchor_entry_id, event_count, decision_source)
SELECT review.session_id, review.anchor_entry_id, review.event_count, 'reviewed'
FROM t_session_event_migration_review review
JOIN t_session_entries entry
  ON entry.session_id = review.session_id
 AND entry.id = review.anchor_entry_id
WHERE entry.type IN (
    'user.message', 'user.interrupt', 'user.tool_confirmation',
    'assistant.message.started', 'assistant.message.delta', 'assistant.message.completed',
    'assistant.thinking.started', 'assistant.thinking.delta', 'assistant.thinking.completed',
    'tool.execution.started', 'tool.execution.delta', 'tool.execution.completed', 'tool.result',
    'session.model.changed', 'session.thinking.changed', 'session.compaction.started',
    'session.compaction.completed', 'session.compaction.failed', 'session.status.idle',
    'stream.end', 'stream.error', 'leaf', 'branch_summary', 'label'
)
AND NOT EXISTS (
    SELECT 1
    FROM tmp_session_event_decisions decision
    WHERE decision.session_id = review.session_id
      AND decision.anchor_entry_id = review.anchor_entry_id
)
AND review.event_count = (
    SELECT COUNT(1)
    FROM t_session_event_migration_events event
    WHERE event.session_id = review.session_id
      AND event.anchor_entry_id = review.anchor_entry_id
)
AND (
    review.event_count = 0
    OR (
        SELECT MIN(event.event_order) = 1 AND MAX(event.event_order) = review.event_count
        FROM t_session_event_migration_events event
        WHERE event.session_id = review.session_id
          AND event.anchor_entry_id = review.anchor_entry_id
    )
)
AND f_session_event_mapping_count_valid(entry.type, review.event_count)
AND NOT EXISTS (
    SELECT 1
    FROM t_session_event_migration_events event
    WHERE event.session_id = review.session_id
      AND event.anchor_entry_id = review.anchor_entry_id
      AND (
          f_validate_session_event_v2(event.type, event.payload) IS NOT TRUE
          OR f_session_event_mapping_type_valid(entry.type, event.type) IS NOT TRUE
      )
);

CREATE TEMP TABLE tmp_session_event_candidates (
    session_id      VARCHAR(128)   NOT NULL,
    anchor_entry_id VARCHAR(128)   NOT NULL,
    entry_seq       BIGINT         NOT NULL,
    event_order     INTEGER        NOT NULL,
    event_id        VARCHAR(128)   NOT NULL,
    type            VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    payload         JSONB          NOT NULL
);

INSERT INTO tmp_session_event_candidates
SELECT entry.session_id, entry.id, entry.entry_seq, 1, entry.id, 'session.model_changed',
       DATE_TRUNC('milliseconds', entry.timestamp),
       JSON_BUILD_OBJECT(
           'previousModelId', entry.payload ->> 'previousModelId',
           'modelId', entry.payload ->> 'modelId',
           'reason', entry.payload ->> 'reason'
       )::JSONB
FROM t_session_entries entry
JOIN tmp_session_event_decisions decision
  ON decision.session_id = entry.session_id
 AND decision.anchor_entry_id = entry.id
 AND decision.decision_source = 'auto_model';

INSERT INTO tmp_session_event_candidates
SELECT entry.session_id, entry.id, entry.entry_seq, 1, entry.id, 'session.thinking_changed',
       DATE_TRUNC('milliseconds', entry.timestamp),
       JSON_BUILD_OBJECT(
           'previousThinking', (entry.payload ->> 'previousThinking')::BOOLEAN,
           'thinking', (entry.payload ->> 'thinking')::BOOLEAN,
           'reason', entry.payload ->> 'reason'
       )::JSONB
FROM t_session_entries entry
JOIN tmp_session_event_decisions decision
  ON decision.session_id = entry.session_id
 AND decision.anchor_entry_id = entry.id
 AND decision.decision_source = 'auto_thinking';

INSERT INTO tmp_session_event_candidates
SELECT entry.session_id, entry.id, entry.entry_seq, 1, entry.id, 'session.compacted',
       DATE_TRUNC('milliseconds', entry.timestamp),
       JSON_BUILD_OBJECT(
           'reason', 'manual',
           'tokensBefore', (entry.payload ->> 'tokensBefore')::BIGINT,
           'estimatedTokensAfter', (entry.payload ->> 'estimatedTokensAfter')::BIGINT
       )::JSONB
FROM t_session_entries entry
JOIN tmp_session_event_decisions decision
  ON decision.session_id = entry.session_id
 AND decision.anchor_entry_id = entry.id
 AND decision.decision_source = 'auto_manual_compaction';

INSERT INTO tmp_session_event_candidates
SELECT event.session_id, event.anchor_entry_id, entry.entry_seq, event.event_order,
       event.event_id, event.type, DATE_TRUNC('milliseconds', event.created_at), event.payload
FROM t_session_event_migration_events event
JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
JOIN tmp_session_event_decisions decision
  ON decision.session_id = event.session_id
 AND decision.anchor_entry_id = event.anchor_entry_id
 AND decision.decision_source = 'reviewed';

CREATE TEMP TABLE tmp_session_all_public_events (
    session_id      VARCHAR(128) NOT NULL,
    event_id        VARCHAR(128) NOT NULL,
    anchor_entry_id VARCHAR(128) NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL
);

INSERT INTO tmp_session_all_public_events
SELECT session_id, event_id, anchor_entry_id, type, payload
FROM t_session_events;

INSERT INTO tmp_session_all_public_events
SELECT session_id, event_id, anchor_entry_id, type, payload
FROM tmp_session_event_candidates;

CREATE TEMP TABLE tmp_session_event_migration_issues (
    session_id VARCHAR(128) NOT NULL,
    reason     VARCHAR(64)  NOT NULL
);

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT entry.session_id, 'UNMAPPED_ENTRY'
FROM t_session_entries entry
LEFT JOIN tmp_session_event_decisions decision
  ON decision.session_id = entry.session_id
 AND decision.anchor_entry_id = entry.id
WHERE decision.anchor_entry_id IS NULL;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT projection.session_id, 'EVENT_COUNT_MISMATCH'
FROM t_session_event_projection projection
LEFT JOIN t_session_events event
  ON event.session_id = projection.session_id
 AND event.anchor_entry_id = projection.anchor_entry_id
GROUP BY projection.session_id, projection.anchor_entry_id, projection.event_count
HAVING projection.event_count != COUNT(event.event_id);

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'EVENT_WITHOUT_PROJECTION'
FROM t_session_events event
LEFT JOIN t_session_event_projection projection
  ON projection.session_id = event.session_id
 AND projection.anchor_entry_id = event.anchor_entry_id
WHERE projection.anchor_entry_id IS NULL;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT projection.session_id, 'PARTIAL_PROJECTED_HISTORY'
FROM t_session_event_projection projection
WHERE EXISTS (
      SELECT 1
      FROM t_session_entries entry
      LEFT JOIN t_session_event_projection current_projection
        ON current_projection.session_id = entry.session_id
       AND current_projection.anchor_entry_id = entry.id
      WHERE entry.session_id = projection.session_id
        AND current_projection.anchor_entry_id IS NULL
  );

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT review.session_id, 'INVALID_REVIEW_INPUT'
FROM t_session_event_migration_review review
LEFT JOIN t_session_entries entry
  ON entry.session_id = review.session_id
 AND entry.id = review.anchor_entry_id
LEFT JOIN tmp_session_event_decisions decision
  ON decision.session_id = review.session_id
 AND decision.anchor_entry_id = review.anchor_entry_id
WHERE entry.id IS NULL
   OR decision.decision_source IS NULL
   OR decision.decision_source NOT IN ('reviewed', 'existing');

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT projection.session_id, 'ORPHAN_PROJECTION'
FROM t_session_event_projection projection
LEFT JOIN t_session_entries entry
  ON entry.session_id = projection.session_id
 AND entry.id = projection.anchor_entry_id
WHERE entry.id IS NULL;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'ORPHAN_PUBLIC_EVENT'
FROM tmp_session_all_public_events event
LEFT JOIN t_session_entries entry
  ON entry.session_id = event.session_id
 AND entry.id = event.anchor_entry_id
WHERE entry.id IS NULL;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'ORPHAN_REVIEWED_EVENT'
FROM t_session_event_migration_events event
LEFT JOIN t_session_event_migration_review review
  ON review.session_id = event.session_id
 AND review.anchor_entry_id = event.anchor_entry_id
WHERE review.anchor_entry_id IS NULL;

INSERT INTO tmp_session_event_migration_issues
SELECT session_id, 'DUPLICATE_EVENT_ID'
FROM tmp_session_all_public_events
GROUP BY session_id, event_id
HAVING COUNT(1) > 1;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'INVALID_PUBLIC_PAYLOAD'
FROM tmp_session_all_public_events event
WHERE f_validate_session_event_v2(event.type, event.payload) IS NOT TRUE;

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'SOURCE_EVENT_MISSING'
FROM tmp_session_all_public_events event
WHERE event.type IN (
    'agent.message', 'agent.thinking', 'agent.tool_call', 'agent.tool_result', 'session.status_idle'
)
AND NOT EXISTS (
    SELECT 1
    FROM tmp_session_all_public_events source
    WHERE source.session_id = event.session_id
      AND source.event_id = event.payload ->> 'sourceEventId'
      AND source.type = 'user.message'
);

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'TARGET_EVENT_MISSING'
FROM tmp_session_all_public_events event
WHERE event.type = 'user.interrupt'
AND NOT EXISTS (
    SELECT 1
    FROM tmp_session_all_public_events target
    WHERE target.session_id = event.session_id
      AND target.event_id = event.payload ->> 'targetEventId'
      AND target.type = 'user.message'
);

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'TOOL_CALL_MISSING'
FROM tmp_session_all_public_events event
WHERE event.type IN ('user.tool_confirmation', 'agent.tool_result')
AND NOT EXISTS (
    SELECT 1
    FROM tmp_session_all_public_events tool_call
    WHERE tool_call.session_id = event.session_id
      AND tool_call.type = 'agent.tool_call'
      AND tool_call.payload ->> 'toolCallId' = event.payload ->> 'toolCallId'
);

INSERT INTO tmp_session_event_migration_issues
SELECT DISTINCT event.session_id, 'COMPACTION_SOURCE_INVALID'
FROM tmp_session_all_public_events event
WHERE event.type = 'session.compacted'
  AND (
      COALESCE(event.payload ->> 'reason', '') NOT IN ('manual', 'threshold', 'overflow')
      OR (event.payload ->> 'reason' = 'manual' AND event.payload ? 'sourceEventId')
      OR (
          event.payload ->> 'reason' IN ('threshold', 'overflow')
          AND NOT EXISTS (
              SELECT 1
              FROM tmp_session_all_public_events source
              WHERE source.session_id = event.session_id
                AND source.event_id = event.payload ->> 'sourceEventId'
                AND source.type = 'user.message'
          )
      )
  );

CREATE TEMP TABLE tmp_session_event_migration_sessions (
    session_id VARCHAR(128) PRIMARY KEY
);

INSERT INTO tmp_session_event_migration_sessions
SELECT session.id
FROM t_sessions session
JOIN t_session_sequences sequence
  ON sequence.session_id = session.id
WHERE NOT EXISTS (
    SELECT 1
    FROM tmp_session_event_migration_issues issue
    WHERE issue.session_id = session.id
)
AND (
    EXISTS (
        SELECT 1
        FROM t_session_entries entry
        LEFT JOIN t_session_event_projection projection
          ON projection.session_id = entry.session_id
         AND projection.anchor_entry_id = entry.id
        WHERE entry.session_id = session.id
          AND projection.anchor_entry_id IS NULL
    )
    OR EXISTS (
        SELECT 1
        FROM t_session_event_migration_review review
        WHERE review.session_id = session.id
    )
)
ORDER BY session.id
LIMIT 500;

CREATE TEMP TABLE tmp_session_new_events (
    session_id      VARCHAR(128)   NOT NULL,
    event_id        VARCHAR(128)   NOT NULL,
    event_seq       BIGINT         NOT NULL,
    anchor_entry_id VARCHAR(128)   NOT NULL,
    type            VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    payload         JSONB          NOT NULL
);

INSERT INTO tmp_session_new_events
SELECT candidate.session_id, candidate.event_id,
       sequence.next_seq + ROW_NUMBER() OVER (
           PARTITION BY candidate.session_id
           ORDER BY candidate.entry_seq, candidate.event_order, candidate.event_id
       ) - 1,
       candidate.anchor_entry_id, candidate.type, candidate.created_at, candidate.payload
FROM tmp_session_event_candidates candidate
JOIN tmp_session_event_migration_sessions migration
  ON migration.session_id = candidate.session_id
JOIN t_session_sequences sequence
  ON sequence.session_id = candidate.session_id;

INSERT INTO t_session_events (
    session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
)
SELECT session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload
FROM tmp_session_new_events
ORDER BY session_id, event_seq;

WITH increments AS (
    SELECT session_id, COUNT(1) AS event_count
    FROM tmp_session_new_events
    GROUP BY session_id
)
UPDATE t_session_sequences sequence
SET next_seq = sequence.next_seq + increments.event_count
FROM increments
WHERE sequence.session_id = increments.session_id;

INSERT INTO t_session_event_projection (session_id, anchor_entry_id, event_count, mapping_source)
SELECT decision.session_id, decision.anchor_entry_id, decision.event_count, 'migration'
FROM tmp_session_event_decisions decision
JOIN tmp_session_event_migration_sessions migration
  ON migration.session_id = decision.session_id
WHERE decision.decision_source != 'existing';

DELETE FROM t_session_event_migration_events event
USING tmp_session_event_migration_sessions migration
WHERE event.session_id = migration.session_id;

DELETE FROM t_session_event_migration_review review
USING tmp_session_event_migration_sessions migration
WHERE review.session_id = migration.session_id;

DROP TABLE tmp_session_new_events;
DROP TABLE tmp_session_event_migration_sessions;
DROP TABLE tmp_session_event_migration_issues;
DROP TABLE tmp_session_all_public_events;
DROP TABLE tmp_session_event_candidates;
DROP TABLE tmp_session_event_decisions;

COMMIT;
