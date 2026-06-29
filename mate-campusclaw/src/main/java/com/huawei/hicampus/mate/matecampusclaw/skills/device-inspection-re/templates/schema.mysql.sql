-- device-inspection-re (rules_re.json) — MySQL 8.0+
-- Shared by device-inspection-re (write) and campus-device-ops (read).
--
-- Usage:
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS campus_inspection DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
--   mysql -u root -p campus_inspection < schema.mysql.sql
--
-- Future Python connection (after db_store MySQL backend is wired):
--   DEVICE_INSPECTION_RE_DB_BACKEND=mysql
--   DEVICE_INSPECTION_RE_DB_URL=mysql+pymysql://user:pass@127.0.0.1:3306/campus_inspection?charset=utf8mb4
--   CAMPUS_OPS_DB_URL=...  (same database)

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------------
-- inspection_runs: one row per inspection execution
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_runs (
    run_id              VARCHAR(64)  NOT NULL COMMENT 'Primary key, e.g. insp_20260611_093543',
    rules_path          TEXT         NOT NULL COMMENT 'Absolute path to rules_re.json used for this run',
    rules_kind          VARCHAR(32)  NOT NULL DEFAULT 'rules_re.json' COMMENT 'Rule bundle kind',
    end_ts              DOUBLE       NOT NULL COMMENT 'Inspection end time (Unix epoch seconds, UTC)',
    fault_device_count  INT          NOT NULL COMMENT 'Distinct devices with at least one alert',
    total_alert_count   INT          NOT NULL COMMENT 'Total alert rows for this run',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Run persisted at (UTC recommended)',
    scope_device_types  TEXT         NULL COMMENT 'JSON array of deviceType filters; NULL = full inspection',
    PRIMARY KEY (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Device inspection run metadata';

-- ---------------------------------------------------------------------------
-- inspection_alarms: one row per matched rule per device
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_alarms (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    run_id            VARCHAR(64)  NOT NULL COMMENT 'FK → inspection_runs.run_id',
    device_id         VARCHAR(128) NOT NULL COMMENT 'Canonical device id, e.g. VAV_002',
    rule_id           VARCHAR(128) NOT NULL COMMENT 'Rule id from rules_re.json',
    rule_name         VARCHAR(256) NOT NULL COMMENT 'Human-readable fault name',
    message           TEXT         NULL COMMENT 'Alert summary message',
    reason_analysis   TEXT         NULL COMMENT '原因分析',
    expert_advice     TEXT         NULL COMMENT '专家处理建议',
    device_type       VARCHAR(64)  NULL COMMENT 'From rule meta.deviceType',
    component         VARCHAR(128) NULL COMMENT 'From rule meta.component',
    device_name       VARCHAR(256) NULL COMMENT 'From device registry',
    building          VARCHAR(64)  NULL COMMENT 'Building / 楼栋',
    floor             VARCHAR(32)  NULL COMMENT 'Floor',
    room              VARCHAR(64)  NULL COMMENT 'Room',
    PRIMARY KEY (id),
    CONSTRAINT fk_inspection_alarms_run
        FOREIGN KEY (run_id) REFERENCES inspection_runs (run_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Inspection alerts for a run';

CREATE INDEX idx_di_re_alarms_run ON inspection_alarms (run_id);
CREATE INDEX idx_di_re_alarms_device ON inspection_alarms (device_id);
CREATE INDEX idx_di_re_alarms_rule ON inspection_alarms (rule_id);
CREATE INDEX idx_di_re_alarms_device_type ON inspection_alarms (device_type);
CREATE INDEX idx_di_re_alarms_building ON inspection_alarms (building);
CREATE INDEX idx_di_re_alarms_run_device ON inspection_alarms (run_id, device_id);

SET FOREIGN_KEY_CHECKS = 1;
