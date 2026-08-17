-- CampusClaw Runtime HTTP V1 Session schema upgrade.
-- Compatible application window: deploy this DDL before a Runtime HTTP V1 application instance.
-- Rollback: restore the pre-upgrade backup; dropping populated columns is intentionally unsupported.
-- Lock impact: t_sessions is rewritten while defaults and NOT NULL constraints are installed.
-- Expected rows: all existing t_sessions rows are retained as owner_id='legacy'.
-- Batching: the release platform must schedule a maintenance window for large t_sessions tables.

BEGIN;

ALTER TABLE t_sessions ADD COLUMN agent_id VARCHAR(30) DEFAULT 'agent_000000000000000000000000' NOT NULL;
ALTER TABLE t_sessions ADD COLUMN owner_id VARCHAR(128) DEFAULT 'legacy' NOT NULL;
ALTER TABLE t_sessions ADD COLUMN bundle_revision VARCHAR(128) DEFAULT 'legacy' NOT NULL;
ALTER TABLE t_sessions ADD COLUMN model_id VARCHAR(128) DEFAULT 'unknown' NOT NULL;
ALTER TABLE t_sessions ADD COLUMN state VARCHAR(16) DEFAULT 'idle' NOT NULL;
ALTER TABLE t_sessions ADD COLUMN thinking BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE t_sessions ADD COLUMN resource_version BIGINT DEFAULT 1 NOT NULL;
ALTER TABLE t_sessions ADD COLUMN updated_at TIMESTAMPTZ(3);

UPDATE t_sessions SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE t_sessions ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE t_sessions ALTER COLUMN agent_id DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN owner_id DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN bundle_revision DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN model_id DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN state DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN thinking DROP DEFAULT;
ALTER TABLE t_sessions ALTER COLUMN resource_version DROP DEFAULT;

CREATE INDEX idx_t_sessions_owner ON t_sessions (owner_id);

ALTER TABLE t_sessions
    ADD CONSTRAINT ck_t_sessions_state CHECK (state IN ('idle', 'running'));
ALTER TABLE t_sessions
    ADD CONSTRAINT ck_t_sessions_resource_version CHECK (resource_version > 0);

CREATE TABLE t_session_tombstone (
    session_id  VARCHAR(128)   PRIMARY KEY,
    deleted_at  TIMESTAMPTZ(3) NOT NULL
);

CREATE TABLE t_session_cleanup_task (
    session_id      VARCHAR(128)   PRIMARY KEY,
    state           VARCHAR(16)    NOT NULL,
    attempt_count   INTEGER        NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    updated_at      TIMESTAMPTZ(3) NOT NULL,
    next_attempt_at TIMESTAMPTZ(3),
    last_error      VARCHAR(512),
    CONSTRAINT ck_t_session_cleanup_state CHECK (state IN ('PENDING', 'RUNNING', 'RETRY')),
    CONSTRAINT ck_t_session_cleanup_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_t_session_cleanup_due
    ON t_session_cleanup_task (state, next_attempt_at, updated_at, created_at);

COMMENT ON COLUMN t_sessions.agent_id IS '创建会话时固定且不可变的 Agent 标识';
COMMENT ON COLUMN t_sessions.owner_id IS '创建该会话的公司调用方标识，用于资源级授权';
COMMENT ON COLUMN t_sessions.bundle_revision IS '创建会话时固定的 Agent 发布快照修订号';
COMMENT ON COLUMN t_sessions.model_id IS '后续用户事件默认使用的当前模型标识';
COMMENT ON COLUMN t_sessions.state IS '会话粗粒度运行状态，仅允许 idle 或 running';
COMMENT ON COLUMN t_sessions.thinking IS '后续用户事件是否启用深度思考';
COMMENT ON COLUMN t_sessions.resource_version IS '配置资源版本号，用于生成强 ETag 和条件更新';
COMMENT ON COLUMN t_sessions.updated_at IS '会话配置或运行状态最后更新时间';
COMMENT ON TABLE t_session_tombstone IS '会话永久删除墓碑表，只保留不可复用的会话标识和删除时间';
COMMENT ON COLUMN t_session_tombstone.session_id IS '已删除且永不复用的会话 ID';
COMMENT ON COLUMN t_session_tombstone.deleted_at IS '会话完成逻辑删除的时间';
COMMENT ON TABLE t_session_cleanup_task IS '会话逻辑删除后用于异步物理清理的短期可重试任务表';
COMMENT ON COLUMN t_session_cleanup_task.session_id IS '需要清理 Runtime 数据的会话 ID';
COMMENT ON COLUMN t_session_cleanup_task.state IS '清理任务状态：PENDING、RUNNING 或 RETRY';
COMMENT ON COLUMN t_session_cleanup_task.attempt_count IS '已经执行清理的次数';
COMMENT ON COLUMN t_session_cleanup_task.created_at IS '清理任务创建时间';
COMMENT ON COLUMN t_session_cleanup_task.updated_at IS '清理任务状态最后更新时间';
COMMENT ON COLUMN t_session_cleanup_task.next_attempt_at IS '失败后允许再次执行的最早时间';
COMMENT ON COLUMN t_session_cleanup_task.last_error IS '最近一次清理失败的脱敏摘要';

COMMIT;
