-- CampusClaw Session storage full installation DDL for centralized GaussDB.
-- The database release platform must connect with the target Session schema as current_schema.
-- AgentService must never execute this file.
-- WARNING: This script destructively rebuilds the complete Session schema baseline.
-- Use upgrade scripts for an existing installation that must retain data.

BEGIN;

DROP TABLE IF EXISTS t_session_materialized;
DROP TABLE IF EXISTS t_session_sequences;
DROP TABLE IF EXISTS t_session_entries;
DROP TABLE IF EXISTS t_session_cleanup_task;
DROP TABLE IF EXISTS t_session_tombstone;
DROP TABLE IF EXISTS t_sessions;

CREATE TABLE t_sessions (
    id                 VARCHAR(128)   PRIMARY KEY,
    agent_id           VARCHAR(30)    NOT NULL,
    model_id           VARCHAR(128)   NOT NULL,
    state              VARCHAR(16)    NOT NULL,
    thinking           BOOLEAN        NOT NULL,
    resource_version   BIGINT         NOT NULL,
    created_at         TIMESTAMPTZ(3) NOT NULL,
    updated_at         TIMESTAMPTZ(3) NOT NULL,
    cwd                VARCHAR(512)   NOT NULL,
    parent_session_id  VARCHAR(128),
    metadata           JSONB,
    active_leaf_id     VARCHAR(128)
);

COMMENT ON TABLE t_sessions IS '会话主表，保存会话元数据和当前路径末端';
COMMENT ON COLUMN t_sessions.id IS '会话的唯一 ID，用于关联该会话的历史记录、序号和汇总数据';
COMMENT ON COLUMN t_sessions.agent_id IS '创建会话时固定且不可变的 Agent 标识';
COMMENT ON COLUMN t_sessions.model_id IS '后续用户事件默认使用的当前模型标识';
COMMENT ON COLUMN t_sessions.state IS '会话粗粒度运行状态，仅允许 idle 或 running';
COMMENT ON COLUMN t_sessions.thinking IS '后续用户事件是否启用深度思考';
COMMENT ON COLUMN t_sessions.resource_version IS '配置资源版本号，用于生成强 ETag 和条件更新';
COMMENT ON COLUMN t_sessions.created_at IS '创建这个会话的时间';
COMMENT ON COLUMN t_sessions.updated_at IS '会话配置或运行状态最后更新时间';
COMMENT ON COLUMN t_sessions.cwd IS '创建会话时使用的工作目录；可用于按工作目录筛选会话';
COMMENT ON COLUMN t_sessions.parent_session_id IS '当前会话复制自哪个来源会话；没有来源时为空，复制内容只包含来源会话的当前路径';
COMMENT ON COLUMN t_sessions.metadata IS '创建会话时由调用方提供的附加信息 JSON；未提供时为 SQL NULL';
COMMENT ON COLUMN t_sessions.active_leaf_id IS '当前路径最后一条历史记录的 ID；空会话时为空，普通读取只展示从该记录回溯得到的路径';

CREATE INDEX idx_t_sessions_created_at
    ON t_sessions (created_at DESC);

CREATE INDEX idx_t_sessions_cwd
    ON t_sessions (cwd);

CREATE INDEX idx_t_sessions_parent
    ON t_sessions (parent_session_id);

ALTER TABLE t_sessions
    ADD CONSTRAINT ck_t_sessions_state CHECK (state IN ('idle', 'running'));

ALTER TABLE t_sessions
    ADD CONSTRAINT ck_t_sessions_resource_version CHECK (resource_version > 0);

CREATE TABLE t_session_tombstone (
    session_id  VARCHAR(128)   PRIMARY KEY,
    deleted_at  TIMESTAMPTZ(3) NOT NULL
);

COMMENT ON TABLE t_session_tombstone IS '会话永久删除墓碑表，只保留不可复用的会话标识和删除时间';
COMMENT ON COLUMN t_session_tombstone.session_id IS '已删除且永不复用的会话 ID';
COMMENT ON COLUMN t_session_tombstone.deleted_at IS '会话完成逻辑删除的时间';

CREATE TABLE t_session_cleanup_task (
    session_id      VARCHAR(128)   PRIMARY KEY,
    state           VARCHAR(16)    NOT NULL,
    attempt_count   INTEGER        NOT NULL,
    created_at      TIMESTAMPTZ(3) NOT NULL,
    updated_at      TIMESTAMPTZ(3) NOT NULL,
    next_attempt_at TIMESTAMPTZ(3),
    last_error      VARCHAR(512)
);

COMMENT ON TABLE t_session_cleanup_task IS '会话逻辑删除后用于异步物理清理的短期可重试任务表';
COMMENT ON COLUMN t_session_cleanup_task.session_id IS '需要清理 Runtime 数据的会话 ID';
COMMENT ON COLUMN t_session_cleanup_task.state IS '清理任务状态：PENDING、RUNNING 或 RETRY';
COMMENT ON COLUMN t_session_cleanup_task.attempt_count IS '已经执行清理的次数';
COMMENT ON COLUMN t_session_cleanup_task.created_at IS '清理任务创建时间';
COMMENT ON COLUMN t_session_cleanup_task.updated_at IS '清理任务状态最后更新时间';
COMMENT ON COLUMN t_session_cleanup_task.next_attempt_at IS '失败后允许再次执行的最早时间';
COMMENT ON COLUMN t_session_cleanup_task.last_error IS '最近一次清理失败的脱敏摘要';

ALTER TABLE t_session_cleanup_task
    ADD CONSTRAINT ck_t_session_cleanup_state CHECK (state IN ('PENDING', 'RUNNING', 'RETRY'));

ALTER TABLE t_session_cleanup_task
    ADD CONSTRAINT ck_t_session_cleanup_attempt CHECK (attempt_count >= 0);

CREATE INDEX idx_t_session_cleanup_due
    ON t_session_cleanup_task (state, next_attempt_at, updated_at, created_at);

CREATE TABLE t_session_entries (
    session_id  VARCHAR(128)   NOT NULL,
    id          VARCHAR(128)   NOT NULL,
    entry_seq   BIGINT         NOT NULL,
    parent_id   VARCHAR(128),
    type        VARCHAR(64)    NOT NULL,
    timestamp   TIMESTAMPTZ(3) NOT NULL,
    payload     JSONB          NOT NULL,
    PRIMARY KEY (session_id, id)
);

COMMENT ON TABLE t_session_entries IS '会话历史记录表，保存可分支回溯的事件数据';
COMMENT ON COLUMN t_session_entries.session_id IS '这条历史记录属于哪个会话；对应 t_sessions.id';
COMMENT ON COLUMN t_session_entries.id IS '这条历史记录的 ID；在同一个会话内唯一';
COMMENT ON COLUMN t_session_entries.entry_seq IS '这条历史记录在会话中的持久化顺序号；从 1 开始且严格递增，不表示当前聊天路径';
COMMENT ON COLUMN t_session_entries.parent_id IS '这条历史记录的直接父记录 ID；根记录为空，系统按该字段回溯当前路径，并保留编辑前的旧路径';
COMMENT ON COLUMN t_session_entries.type IS '这条历史记录的种类；新写入不接受 leaf、branch_summary 和 label';
COMMENT ON COLUMN t_session_entries.timestamp IS '这条历史记录产生时携带的事件时间；不是数据库保存该行的时间';
COMMENT ON COLUMN t_session_entries.payload IS '这条历史记录的类型相关 JSON 内容；ID、父记录、事件时间和类型分别保存在其他字段中';

CREATE UNIQUE INDEX idx_t_session_entries_session_seq
    ON t_session_entries (session_id, entry_seq);

CREATE INDEX idx_t_session_entries_session_parent
    ON t_session_entries (session_id, parent_id);

CREATE INDEX idx_t_session_entries_session_type
    ON t_session_entries (session_id, type);

CREATE TABLE t_session_sequences (
    session_id  VARCHAR(128) PRIMARY KEY,
    next_seq    BIGINT       NOT NULL
);

COMMENT ON TABLE t_session_sequences IS '会话序号表，分配单个会话内严格递增的持久化顺序号';
COMMENT ON COLUMN t_session_sequences.session_id IS '这行序号记录属于哪个会话；对应 t_sessions.id，每个会话一行';
COMMENT ON COLUMN t_session_sequences.next_seq IS '下一条新历史记录要使用的 entry_seq；新建会话时为 1，每次成功追加后加 1';

CREATE TABLE t_session_materialized (
    session_id  VARCHAR(128) PRIMARY KEY,
    payload     JSONB        NOT NULL
);

COMMENT ON TABLE t_session_materialized IS '会话汇总表，保存当前路径和生命周期用量等物化数据';
COMMENT ON COLUMN t_session_materialized.session_id IS '这份汇总属于哪个会话；对应 t_sessions.id，每个会话一行';
COMMENT ON COLUMN t_session_materialized.payload IS '会话汇总 JSON；activePath 保存当前路径的名称、消息数、模型和思考级别，lifetimeUsage 保存会话自创建以来的 Token 和费用总和';

COMMIT;
