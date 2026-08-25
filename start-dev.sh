#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_JAR="$ROOT_DIR/modules/coding-agent-cli/target/campusclaw-agent.jar"
SCHEMA_SQL="$ROOT_DIR/modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql"
INITIAL_DATA_SQL="$ROOT_DIR/modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_initial_data.sql"

DB_CONTAINER="${CAMPUSCLAW_DB_CONTAINER:-campusclaw-opengauss-test}"
DB_IMAGE="${CAMPUSCLAW_DB_IMAGE:-opengauss/opengauss-server:latest}"
DB_NAME="${CAMPUSCLAW_DB_NAME:-campusclaw}"
DB_SCHEMA="${CAMPUSCLAW_DB_SCHEMA:-campusclaw_session}"
DB_USER="${CAMPUSCLAW_DB_USER:-campusclaw}"
DB_HOST_PORT="${CAMPUSCLAW_DB_PORT:-}"
DB_PASSWORD="${CAMPUSCLAW_DB_PASSWORD:-}"
DB_SSL_MODE="${CAMPUSCLAW_DB_SSL_MODE:-disable}"

BACKEND_PID=""
FRONTEND_PID=""

log() {
    printf '[start-dev] %s\n' "$*"
}

fail() {
    printf '[start-dev] ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "未找到命令：$1"
}

validate_identifier() {
    [[ "$1" =~ ^[a-z_][a-z0-9_]*$ ]] || fail "数据库标识符不合法：$1"
}

port_is_free() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            return 1
        fi
        return 0
    fi
    if command -v nc >/dev/null 2>&1; then
        if nc -z 127.0.0.1 "$port" >/dev/null 2>&1; then
            return 1
        fi
        return 0
    fi
    fail "无法检测端口占用，请安装 lsof 或 nc"
}

find_free_port() {
    local port="$1"
    while ! port_is_free "$port"; do
        port=$((port + 1))
    done
    printf '%s\n' "$port"
}

select_java_21() {
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/java" ]] \
        && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
        JAVA_BIN="$JAVA_HOME/bin/java"
        return
    fi

    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local detected_home
        detected_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [[ -n "$detected_home" ]] && [[ -x "$detected_home/bin/java" ]]; then
            export JAVA_HOME="$detected_home"
            JAVA_BIN="$JAVA_HOME/bin/java"
            return
        fi
    fi

    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"21\.'; then
        JAVA_BIN="$(command -v java)"
        return
    fi

    fail "未找到 JDK 21，请设置 JAVA_HOME"
}

load_existing_database_config() {
    local container_env
    container_env="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$DB_CONTAINER")"

    if [[ -z "$DB_PASSWORD" ]]; then
        DB_PASSWORD="$(printf '%s\n' "$container_env" | sed -n 's/^GS_PASSWORD=//p')"
    fi
    if [[ -z "$DB_PASSWORD" ]]; then
        fail "无法读取数据库密码，请设置 CAMPUSCLAW_DB_PASSWORD"
    fi

    if [[ -z "$DB_HOST_PORT" ]]; then
        local port_mapping
        port_mapping="$(docker port "$DB_CONTAINER" 5432/tcp 2>/dev/null | head -n 1 || true)"
        DB_HOST_PORT="${port_mapping##*:}"
    fi
    [[ -n "$DB_HOST_PORT" ]] || fail "容器没有映射 5432 端口，请设置 CAMPUSCLAW_DB_PORT"
}

ensure_database_container() {
    require_command docker
    validate_identifier "$DB_NAME"
    validate_identifier "$DB_SCHEMA"
    validate_identifier "$DB_USER"

    if docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
        local running
        running="$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER")"
        if [[ "$running" != "true" ]]; then
            log "启动已有数据库容器：$DB_CONTAINER"
            docker start "$DB_CONTAINER" >/dev/null
        fi
        load_existing_database_config
    else
        [[ -n "$DB_HOST_PORT" ]] || DB_HOST_PORT=35432
        [[ -n "$DB_PASSWORD" ]] || DB_PASSWORD="$(openssl rand -hex 16 2>/dev/null || printf 'CampusClaw@123')"
        port_is_free "$DB_HOST_PORT" || fail "数据库端口已被占用：$DB_HOST_PORT"
        log "创建本地 openGauss 容器：$DB_CONTAINER"
        docker run -d \
            --name "$DB_CONTAINER" \
            -e "GS_USERNAME=$DB_USER" \
            -e "GS_PASSWORD=$DB_PASSWORD" \
            -p "$DB_HOST_PORT:5432" \
            "$DB_IMAGE" >/dev/null
    fi
}

gsql() {
    docker exec \
        -e "LD_LIBRARY_PATH=/usr/local/opengauss/lib" \
        "$DB_CONTAINER" \
        /usr/local/opengauss/bin/gsql \
        -h 127.0.0.1 \
        -p 5432 \
        -U "$DB_USER" \
        -W "$DB_PASSWORD" \
        "$@"
}

gsql_in_schema() {
    docker exec \
        -e "LD_LIBRARY_PATH=/usr/local/opengauss/lib" \
        -e "PGOPTIONS=-c search_path=$DB_SCHEMA" \
        "$DB_CONTAINER" \
        /usr/local/opengauss/bin/gsql \
        -h 127.0.0.1 \
        -p 5432 \
        -U "$DB_USER" \
        -W "$DB_PASSWORD" \
        "$@"
}

wait_for_database() {
    for _ in $(seq 1 60); do
        if gsql -d postgres -Atc 'SELECT 1' >/dev/null 2>&1; then
            return
        fi
        sleep 2
    done
    docker logs --tail 40 "$DB_CONTAINER" >&2 || true
    fail "openGauss 在规定时间内未就绪"
}

initialize_database() {
    local database_exists table_count

    database_exists="$(gsql -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME';" \
        | tr -d '[:space:]')"
    if [[ "$database_exists" != "1" ]]; then
        log "创建数据库：$DB_NAME"
        gsql -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $DB_NAME;"
    fi

    gsql -d "$DB_NAME" -v ON_ERROR_STOP=1 \
        -c "CREATE SCHEMA IF NOT EXISTS $DB_SCHEMA AUTHORIZATION $DB_USER;" >/dev/null

    table_count="$(gsql_in_schema -d "$DB_NAME" -Atc \
        "SELECT count(*) FROM pg_tables WHERE schemaname = '$DB_SCHEMA' \
         AND tablename IN ('t_sessions', 't_session_entries', 't_session_sequences', \
                           't_session_materialized', 't_session_tombstone', 't_session_cleanup_task', \
                           't_session_records', 't_session_stats');" \
        | tr -d '[:space:]')"

    case "$table_count" in
        8)
            log "数据库表结构已存在，跳过初始化"
            ;;
        0)
            log "初始化 Runtime 数据库表结构"
            gsql_in_schema -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$SCHEMA_SQL" >/dev/null
            gsql_in_schema -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$INITIAL_DATA_SQL" >/dev/null
            ;;
        *)
            fail "Runtime 表结构不完整（当前 $table_count/8 张表），为避免破坏数据，脚本不会自动重建"
            ;;
    esac
}

needs_backend_build() {
    [[ ! -f "$BACKEND_JAR" ]] && return 0
    [[ "$ROOT_DIR/pom.xml" -nt "$BACKEND_JAR" ]] && return 0
    [[ "$ROOT_DIR/modules/coding-agent-cli/pom.xml" -nt "$BACKEND_JAR" ]] && return 0
    [[ -n "$(find "$ROOT_DIR/modules" -type f \
        \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.yaml' \
           -o -name '*.properties' -o -name '*.sql' \) \
        -newer "$BACKEND_JAR" -print -quit)" ]]
}

build_backend_if_needed() {
    if needs_backend_build; then
        log "检测到后端源码变化，重新构建 JAR"
        "$ROOT_DIR/mvnw" -f "$ROOT_DIR/pom.xml" package \
            -pl modules/coding-agent-cli -am -q -DskipTests
    else
        log "后端 JAR 未变化，跳过构建"
    fi
}

install_frontend_dependencies() {
    if [[ ! -d "$ROOT_DIR/frontend/node_modules" ]] \
        || [[ ! -f "$ROOT_DIR/frontend/node_modules/.package-lock.json" ]] \
        || [[ "$ROOT_DIR/frontend/package-lock.json" -nt "$ROOT_DIR/frontend/node_modules/.package-lock.json" ]]; then
        log "安装前端依赖"
        (cd "$ROOT_DIR/frontend" && npm ci)
    fi
}

cleanup() {
    trap - EXIT INT TERM
    if [[ -n "$FRONTEND_PID" ]] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
        kill "$FRONTEND_PID" 2>/dev/null || true
    fi
    if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
        kill "$BACKEND_PID" 2>/dev/null || true
    fi
    wait "$FRONTEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
}

wait_for_processes() {
    while true; do
        if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
            fail "后端进程已退出"
        fi
        if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
            fail "前端进程已退出"
        fi
        sleep 1
    done
}

require_command npm
select_java_21
ensure_database_container
wait_for_database
initialize_database
build_backend_if_needed
install_frontend_dependencies

if [[ -n "${SERVER_PORT:-}" ]]; then
    BACKEND_PORT="$SERVER_PORT"
else
    BACKEND_PORT="$(find_free_port 8080)"
fi
port_is_free "$BACKEND_PORT" || fail "后端端口已被占用：$BACKEND_PORT"

if [[ -z "${FRONTEND_PORT:-}" ]]; then
    FRONTEND_PORT="$(find_free_port 5173)"
fi
port_is_free "$FRONTEND_PORT" || fail "前端端口已被占用：$FRONTEND_PORT"

export GAUSSDB_URL="jdbc:postgresql://127.0.0.1:$DB_HOST_PORT/$DB_NAME"
export GAUSSDB_USER="$DB_USER"
export GAUSSDB_PASSWORD="$DB_PASSWORD"
export GAUSSDB_SCHEMA="$DB_SCHEMA"
export GAUSSDB_SSL_MODE="$DB_SSL_MODE"
export SERVER_PORT="$BACKEND_PORT"
export CAMPUSCLAW_AGENTS_ROOT="${CAMPUSCLAW_AGENTS_ROOT:-$ROOT_DIR/agent}"

trap cleanup EXIT INT TERM

log "启动后端：http://127.0.0.1:$BACKEND_PORT"
(
    exec "$JAVA_BIN" -jar "$BACKEND_JAR"
) &
BACKEND_PID=$!

log "启动前端：http://127.0.0.1:$FRONTEND_PORT"
(
    cd "$ROOT_DIR/frontend"
    VITE_BACKEND_URL="http://127.0.0.1:$BACKEND_PORT" \
        npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT"
) &
FRONTEND_PID=$!

log "数据库：$DB_NAME@$DB_HOST_PORT/$DB_SCHEMA"
log "Agent Runtime 根目录：$CAMPUSCLAW_AGENTS_ROOT"
log "按 Ctrl-C 同时停止前后端；数据库容器会保留"
wait_for_processes
