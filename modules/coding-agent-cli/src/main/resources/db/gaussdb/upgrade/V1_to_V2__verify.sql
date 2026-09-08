-- CampusClaw Events v2 migration verification.
-- Success returns no rows. Output intentionally excludes event payload and review text.

SELECT session_id, anchor_entry_id, entry_type, gap_reason
FROM f_session_event_migration_gaps()
ORDER BY session_id, anchor_entry_id, gap_reason;
