-- campus-device-ops inspection persistence (PostgreSQL reference)
-- Mock runtime uses SQLite file: mock_fixtures/campus_ops.db

CREATE TABLE IF NOT EXISTS inspection_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    rules_path TEXT NOT NULL,
    end_ts DOUBLE PRECISION NOT NULL,
    fault_device_count INTEGER NOT NULL,
    total_alert_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inspection_alarms (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES inspection_runs(run_id) ON DELETE CASCADE,
    device_id VARCHAR(128) NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_name VARCHAR(256) NOT NULL,
    message TEXT,
    reason_analysis TEXT,
    expert_advice TEXT,
    building VARCHAR(64),
    device_name VARCHAR(256),
    device_type VARCHAR(64),
    component VARCHAR(128),
    assignee_id VARCHAR(64),
    assignee_name VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_inspection_alarms_run ON inspection_alarms(run_id);
CREATE INDEX IF NOT EXISTS idx_inspection_alarms_device ON inspection_alarms(device_id);
CREATE INDEX IF NOT EXISTS idx_inspection_alarms_building ON inspection_alarms(building);
CREATE INDEX IF NOT EXISTS idx_inspection_alarms_rule ON inspection_alarms(rule_id);

-- Query examples:
-- Latest run alarms:  SELECT * FROM inspection_alarms WHERE run_id = (SELECT run_id FROM inspection_runs ORDER BY created_at DESC LIMIT 1);
-- By building:       SELECT * FROM inspection_alarms WHERE run_id = ? AND building = 'A栋';
