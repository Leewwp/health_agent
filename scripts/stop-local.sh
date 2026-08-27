#!/usr/bin/env bash
# 停止 start-local.sh 启动的本地环境：杀掉 8082 上的 Spring Boot 后端，删除 Nginx 前端容器。
# 只操作本脚本专属资源（health-agent-local-nginx 容器与本仓库 .local-run 产物），
# 不触碰 docker-compose 的 MySQL/Qdrant 容器与数据卷，本机 MySQL 数据不受任何影响。
#
# 用法：
#   ./scripts/stop-local.sh
#
# 环境变量（与 start-local.sh 保持一致）：
#   BACKEND_PORT        后端端口（默认 8082）
#   NGINX_CONTAINER     Nginx 容器名（默认 health-agent-local-nginx）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$ROOT/.local-run"

BACKEND_PORT="${BACKEND_PORT:-8082}"
NGINX_CONTAINER="${NGINX_CONTAINER:-health-agent-local-nginx}"

log()  { printf '\033[1;32m[stop-local]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[stop-local]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[stop-local]\033[0m %s\n' "$*" >&2; exit 1; }

port_pids() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true
}

backend_healthy() {
  curl -fsS --max-time 2 "http://localhost:${BACKEND_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"'
}

# 连同子进程一起终止（mvn 父进程下挂着真正的 java 业务进程）。
kill_tree() {
  local pid="$1" sig="$2" kid
  for kid in $(pgrep -P "$pid" 2>/dev/null); do
    kill_tree "$kid" "$sig"
  done
  kill "-$sig" "$pid" 2>/dev/null || true
}

looks_like_our_backend() {
  # 通过健康检查身份确认是本应用，避免误杀恰好占用端口的无关服务。
  backend_healthy
}

stopped_any=false

# ---- 1) 后端：优先按 pidfile 终止 mvn 进程树 ----
if [[ -f "$RUN_DIR/backend.pid" ]]; then
  MVN_PID="$(cat "$RUN_DIR/backend.pid" 2>/dev/null || true)"
  if [[ -n "$MVN_PID" ]] && ps -p "$MVN_PID" >/dev/null 2>&1 \
    && ps -p "$MVN_PID" -o command= | grep -Eq 'mvn|spring-boot|java'; then
    log "停止后端进程树（mvn pid ${MVN_PID}）…"
    kill_tree "$MVN_PID" TERM
    for _ in $(seq 1 10); do
      ps -p "$MVN_PID" >/dev/null 2>&1 || break
      sleep 1
    done
    ps -p "$MVN_PID" >/dev/null 2>&1 && kill_tree "$MVN_PID" KILL
    rm -f "$RUN_DIR/backend.pid"
    stopped_any=true
  else
    rm -f "$RUN_DIR/backend.pid"
  fi
fi

# ---- 2) 兜底：清掉仍监听在后端端口的残留进程（仅限确认是本应用的） ----
REMAIN="$(port_pids "$BACKEND_PORT")"
if [[ -n "$REMAIN" ]]; then
  pid_list="$(echo "$REMAIN" | paste -sd, -)"
  if looks_like_our_backend || ps -p "$pid_list" -o command= 2>/dev/null | grep -Eq 'java|spring-boot|mvn'; then
    for pid in $REMAIN; do
      log "清理残留后端进程（pid ${pid}）…"
      kill_tree "$pid" TERM
    done
    sleep 2
    REMAIN="$(port_pids "$BACKEND_PORT")"
    for pid in $REMAIN; do
      kill_tree "$pid" KILL
    done
    stopped_any=true
  fi
fi

# ---- 3) Nginx 前端容器：无状态，直接删除；下次 start 会按当前端口重建 ----
if docker info >/dev/null 2>&1; then
  if docker ps -a --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
    docker rm -f "$NGINX_CONTAINER" >/dev/null
    log "已停止并移除 Nginx 容器 ${NGINX_CONTAINER}。"
    stopped_any=true
  fi
else
  warn "Docker 不可用，跳过 Nginx 容器清理（如容器仍在运行请手动执行：docker rm -f ${NGINX_CONTAINER}）。"
fi

sleep 1
FINAL_PIDS="$(port_pids "$BACKEND_PORT")"
[[ -z "$FINAL_PIDS" ]] || die "端口 ${BACKEND_PORT} 仍被占用（pid: $(echo "$FINAL_PIDS" | tr '\n' ' ')），请手动处理：kill \$(lsof -ti tcp:${BACKEND_PORT} -sTCP:LISTEN)"

cat <<EOF

============================================================
✅ 本地环境已停止
  - Spring Boot 后端（${BACKEND_PORT}）已终止
  - Nginx 前端容器（${NGINX_CONTAINER}）已移除（无状态，不影响数据）
  - docker-compose 的 MySQL/Qdrant 与本机 MySQL 数据均未改动
再次启动: ./scripts/start-local.sh
============================================================
EOF
$stopped_any || warn "本次没有发现正在运行的后端或 Nginx 容器。"
