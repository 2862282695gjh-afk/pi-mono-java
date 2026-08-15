-- device-inspection persistence (PostgreSQL reference)
-- Mock runtime: mock_fixtures/device_inspection.db (SQLite)

CREATE TABLE IF NOT EXISTS inspection_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    rules_path TEXT NOT NULL,
    rules_kind VARCHAR(32) NOT NULL DEFAULT 'rules.json',
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
    device_type VARCHAR(64),
    component VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_di_alarms_run ON inspection_alarms(run_id);
CREATE INDEX IF NOT EXISTS idx_di_alarms_device ON inspection_alarms(device_id);
CREATE INDEX IF NOT EXISTS idx_di_alarms_rule ON inspection_alarms(rule_id);
