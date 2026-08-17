"""
Database backend abstraction for device inspection persistence.

Supports SQLite (default, local mock) and MySQL 8.0+ (production).

Environment:
  DEVICE_INSPECTION_RE_DB_BACKEND=sqlite|mysql   (default: sqlite)
  DEVICE_INSPECTION_RE_DB_PATH                  (sqlite file path)
  DEVICE_INSPECTION_RE_DB_URL                    (mysql://user:pass@host:3306/db)
  CAMPUS_OPS_DB_BACKEND / CAMPUS_OPS_DB_URL      (campus-device-ops aliases)

MySQL schema: templates/schema.mysql.sql
"""
from __future__ import annotations

import os
import re
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence, Set, Union
from urllib.parse import unquote, urlparse

Row = Dict[str, Any]


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def db_path() -> Path:
    env = os.environ.get("DEVICE_INSPECTION_RE_DB_PATH", "").strip()
    if env:
        return Path(env).expanduser().resolve()
    campus = os.environ.get("CAMPUS_OPS_DB_PATH", "").strip()
    if campus:
        return Path(campus).expanduser().resolve()
    return skill_root() / "mock_fixtures" / "device_inspection_re.db"


def backend_name() -> str:
    raw = (
        os.environ.get("DEVICE_INSPECTION_RE_DB_BACKEND", "").strip()
        or os.environ.get("CAMPUS_OPS_DB_BACKEND", "").strip()
        or "sqlite"
    )
    name = raw.lower()
    if name not in ("sqlite", "mysql"):
        raise ValueError(f"unsupported DB backend: {raw!r}; use sqlite or mysql")
    return name


def is_mysql() -> bool:
    return backend_name() == "mysql"


def _parse_mysql_url(url: str) -> Dict[str, Any]:
    text = url.strip()
    if text.startswith("mysql+pymysql://"):
        text = "mysql://" + text[len("mysql+pymysql://") :]
    if not text.startswith("mysql://"):
        raise ValueError(f"invalid MySQL URL (expected mysql://...): {url!r}")
    parsed = urlparse(text)
    database = (parsed.path or "").lstrip("/")
    if not database:
        raise ValueError(f"MySQL URL missing database name: {url!r}")
    return {
        "host": parsed.hostname or "127.0.0.1",
        "port": parsed.port or 3306,
        "user": unquote(parsed.username or ""),
        "password": unquote(parsed.password or ""),
        "database": database,
    }


def mysql_connect_kwargs() -> Dict[str, Any]:
    url = (
        os.environ.get("DEVICE_INSPECTION_RE_DB_URL", "").strip()
        or os.environ.get("CAMPUS_OPS_DB_URL", "").strip()
    )
    if url:
        return _parse_mysql_url(url)
    return {
        "host": os.environ.get("DEVICE_INSPECTION_RE_DB_HOST", "127.0.0.1"),
        "port": int(os.environ.get("DEVICE_INSPECTION_RE_DB_PORT", "3306") or "3306"),
        "user": os.environ.get("DEVICE_INSPECTION_RE_DB_USER", "root"),
        "password": os.environ.get("DEVICE_INSPECTION_RE_DB_PASSWORD", ""),
        "database": os.environ.get("DEVICE_INSPECTION_RE_DB_NAME", "campus_inspection"),
    }


def _adapt_sql(sql: str) -> str:
    if not is_mysql():
        return sql
    return sql.replace("?", "%s")


def _row_to_dict(row: Any) -> Row:
    if row is None:
        return {}
    if isinstance(row, dict):
        return dict(row)
    return dict(row)


class DbCursor:
    def __init__(self, cursor: Any) -> None:
        self._cursor = cursor

    def fetchone(self) -> Optional[Row]:
        row = self._cursor.fetchone()
        return _row_to_dict(row) if row is not None else None

    def fetchall(self) -> List[Row]:
        return [_row_to_dict(r) for r in self._cursor.fetchall()]


class DbConnection:
    def __init__(self, raw: Any, backend: str) -> None:
        self._raw = raw
        self._backend = backend

    @property
    def backend(self) -> str:
        return self._backend

    def execute(self, sql: str, params: Sequence[Any] = ()) -> DbCursor:
        adapted = _adapt_sql(sql)
        if self._backend == "sqlite":
            cur = self._raw.execute(adapted, tuple(params))
            return DbCursor(cur)
        import pymysql

        cur = self._raw.cursor(pymysql.cursors.DictCursor)
        cur.execute(adapted, tuple(params))
        return DbCursor(cur)

    def executescript(self, sql: str) -> None:
        if self._backend != "sqlite":
            for stmt in _split_sql_statements(sql):
                if stmt.strip():
                    self.execute(stmt)
            return
        self._raw.executescript(sql)

    def commit(self) -> None:
        self._raw.commit()

    def close(self) -> None:
        self._raw.close()

    def __enter__(self) -> DbConnection:
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if exc_type is None:
            try:
                self.commit()
            except Exception:
                pass
        self.close()


def _split_sql_statements(sql: str) -> List[str]:
    parts = re.split(r";\s*\n", sql)
    return [p.strip() for p in parts if p.strip() and not p.strip().startswith("--")]


def connect(*, require_exists: bool = False, init: bool = True) -> DbConnection:
    backend = backend_name()
    if backend == "mysql":
        import pymysql

        kwargs = mysql_connect_kwargs()
        raw = pymysql.connect(
            host=kwargs["host"],
            port=int(kwargs["port"]),
            user=kwargs["user"],
            password=kwargs["password"],
            database=kwargs["database"],
            charset="utf8mb4",
            autocommit=False,
        )
        conn = DbConnection(raw, "mysql")
        if init:
            init_schema(conn)
        return conn

    path = db_path()
    if require_exists and not path.is_file():
        raise FileNotFoundError(
            f"inspection database not found: {path}; "
            "run device-inspection-re/scripts/judge_rules_re.py first"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = sqlite3.connect(str(path))
    raw.row_factory = sqlite3.Row
    raw.execute("PRAGMA foreign_keys = ON")
    conn = DbConnection(raw, "sqlite")
    if init:
        init_schema(conn)
    return conn


def table_columns(conn: DbConnection, table: str) -> Set[str]:
    if conn.backend == "sqlite":
        rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
        return {str(r["name"]) for r in rows}
    rows = conn.execute(
        """
        SELECT COLUMN_NAME AS name
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
        ORDER BY ORDINAL_POSITION
        """,
        (table,),
    ).fetchall()
    return {str(r["name"]) for r in rows}


def init_schema(conn: DbConnection) -> None:
    if conn.backend == "sqlite":
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS inspection_runs (
                run_id TEXT PRIMARY KEY,
                rules_path TEXT NOT NULL,
                rules_kind TEXT NOT NULL DEFAULT 'rules_re.json',
                end_ts REAL NOT NULL,
                fault_device_count INTEGER NOT NULL,
                total_alert_count INTEGER NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS inspection_alarms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                rule_id TEXT NOT NULL,
                rule_name TEXT NOT NULL,
                message TEXT,
                reason_analysis TEXT,
                expert_advice TEXT,
                device_type TEXT,
                component TEXT,
                FOREIGN KEY (run_id) REFERENCES inspection_runs(run_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_di_re_alarms_run ON inspection_alarms(run_id);
            CREATE INDEX IF NOT EXISTS idx_di_re_alarms_device ON inspection_alarms(device_id);
            CREATE INDEX IF NOT EXISTS idx_di_re_alarms_rule ON inspection_alarms(rule_id);
            """
        )
        _ensure_sqlite_columns(conn)
        conn.commit()
        return

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS inspection_runs (
            run_id              VARCHAR(64)  NOT NULL,
            rules_path          TEXT         NOT NULL,
            rules_kind          VARCHAR(32)  NOT NULL DEFAULT 'rules_re.json',
            end_ts              DOUBLE       NOT NULL,
            fault_device_count  INT          NOT NULL,
            total_alert_count   INT          NOT NULL,
            created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            scope_device_types  TEXT         NULL,
            PRIMARY KEY (run_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS inspection_alarms (
            id                BIGINT       NOT NULL AUTO_INCREMENT,
            run_id            VARCHAR(64)  NOT NULL,
            device_id         VARCHAR(128) NOT NULL,
            rule_id           VARCHAR(128) NOT NULL,
            rule_name         VARCHAR(256) NOT NULL,
            message           TEXT         NULL,
            reason_analysis   TEXT         NULL,
            expert_advice     TEXT         NULL,
            device_type       VARCHAR(64)  NULL,
            component         VARCHAR(128) NULL,
            device_name       VARCHAR(256) NULL,
            building          VARCHAR(64)  NULL,
            floor             VARCHAR(32)  NULL,
            room              VARCHAR(64)  NULL,
            PRIMARY KEY (id),
            CONSTRAINT fk_inspection_alarms_run
                FOREIGN KEY (run_id) REFERENCES inspection_runs (run_id)
                ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )
    for ddl in (
        "CREATE INDEX idx_di_re_alarms_run ON inspection_alarms (run_id)",
        "CREATE INDEX idx_di_re_alarms_device ON inspection_alarms (device_id)",
        "CREATE INDEX idx_di_re_alarms_rule ON inspection_alarms (rule_id)",
        "CREATE INDEX idx_di_re_alarms_device_type ON inspection_alarms (device_type)",
        "CREATE INDEX idx_di_re_alarms_building ON inspection_alarms (building)",
        "CREATE INDEX idx_di_re_alarms_run_device ON inspection_alarms (run_id, device_id)",
    ):
        try:
            conn.execute(ddl)
        except Exception:
            pass
    conn.commit()


def _ensure_sqlite_columns(conn: DbConnection) -> None:
    run_cols = table_columns(conn, "inspection_runs")
    if "scope_device_types" not in run_cols:
        conn.execute("ALTER TABLE inspection_runs ADD COLUMN scope_device_types TEXT")
    alarm_cols = table_columns(conn, "inspection_alarms")
    for col, ddl in (
        ("device_name", "ALTER TABLE inspection_alarms ADD COLUMN device_name TEXT"),
        ("building", "ALTER TABLE inspection_alarms ADD COLUMN building TEXT"),
        ("floor", "ALTER TABLE inspection_alarms ADD COLUMN floor TEXT"),
        ("room", "ALTER TABLE inspection_alarms ADD COLUMN room TEXT"),
    ):
        if col not in alarm_cols:
            conn.execute(ddl)


@contextmanager
def db_session(*, require_exists: bool = False, init: bool = True) -> Iterator[DbConnection]:
    conn = connect(require_exists=require_exists, init=init)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.close()
        raise
    else:
        conn.close()
