#!/usr/bin/env bash
# 本地一键启动：Maven 后端（默认 8082）+ Nginx 前端容器（默认 8092，同源反代 /api/ 与 /actuator/health）。
# 复用本机已有 MySQL（root/123456，见 application.yml），不走 docker-compose 的 prod profile，
# 因此不需要 DASHSCOPE 之外的生产必填项（DIET_SESSION_SECRET / ADMIN_TOKEN 使用 dev 默认值），
# 也不会与占用 80 端口的既有进程冲突。不影响 Compose 的 MySQL/Qdrant 及其数据卷。
#
# 用法：
#   ./scripts/start-local.sh            # 启动或复用，成功后自动打开浏览器
#   ./scripts/start-local.sh -h         # 查看可配置项
#
# 环境变量覆盖（均可选）：
#   BACKEND_PORT        后端端口（默认 8082）
#   FRONTEND_PORT       前端端口（默认 8092）
#   MYSQL_HOST/PORT     本机 MySQL 检查地址（默认 localhost:3306）
#   WAIT_TIMEOUT_SECS   等待后端 /actuator/health 变为 UP 的超时秒数（默认 300）
#   NO_OPEN=1           成功后不自动打开浏览器

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$ROOT/.local-run"
LOG_DIR="$RUN_DIR/logs"

BACKEND_PORT="${BACKEND_PORT:-8082}"
FRONTEND_PORT="${FRONTEND_PORT:-8092}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
NGINX_CONTAINER="${NGINX_CONTAINER:-health-agent-local-nginx}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-300}"

BACKEND_URL="http://localhost:${BACKEND_PORT}"
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"

log()  { printf '\033[1;32m[start-local]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[start-local]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[start-local]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi

command -v java   >/dev/null 2>&1 || die "未找到 java，请安装 JDK 21"
command -v mvn    >/dev/null 2>&1 || die "未找到 mvn，请安装 Maven 3.9+"
command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装并启动 Docker Desktop"
command -v curl   >/dev/null 2>&1 || die "未找到 curl"
command -v lsof   >/dev/null 2>&1 || die "未找到 lsof"

# Java 版本必须是 21；macOS 上若默认 java 不是 21，尝试自动切换到已安装的 JDK 21。
java_major() {
  java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1
}
if [[ "$(java_major)" != "21" ]]; then
  if [[ "$(uname -s)" == "Darwin" ]] && JAVA21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" && [[ -n "$JAVA21_HOME" ]]; then
    export JAVA_HOME="$JAVA21_HOME"
    PATH="$JAVA_HOME/bin:$PATH"
    log "默认 java 不是 21，已切换 JAVA_HOME 到 ${JAVA_HOME}"
  fi
fi
[[ "$(java_major)" == "21" ]] || die "需要 JDK 21（当前为 $(java -version 2>&1 | head -n1))"

docker info >/dev/null 2>&1 || die "Docker 守护进程不可用，请先启动 Docker Desktop"

mysql_reachable() {
  command -v nc >/dev/null 2>&1 \
    && nc -z -w 2 "$MYSQL_HOST" "$MYSQL_PORT" >/dev/null 2>&1 \
    || (exec 3<>"/dev/tcp/${MYSQL_HOST}/${MYSQL_PORT}") 2>/dev/null
}
mysql_reachable || die "本机 MySQL ${MYSQL_HOST}:${MYSQL_PORT} 不可达。请先启动 MySQL（账号需与 application.yml 一致：root/123456）"

# API key 仅影响真实模型调用：缺失时聊天确定性降级为模板回答，服务本身照常可用。
have_dashscope_key=false
if [[ -n "${DASHSCOPE_API_KEY:-}" ]]; then
  have_dashscope_key=true
elif [[ -f "$ROOT/.env" ]] && grep -Eq '^DASHSCOPE_API_KEY=.+' "$ROOT/.env"; then
  have_dashscope_key=true
fi
$have_dashscope_key || warn "未检测到 DASHSCOPE_API_KEY（系统环境变量与仓库根目录 .env 均无）。服务照常启动，但 LLM 回答会确定性降级为模板文案。"

port_pids() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true
}

mkdir -p "$LOG_DIR"

# ---- 后端端口决策：已被本应用占住则复用，被无关进程占住则报错退出，绝不静默换端口 ----
REUSE_BACKEND=false
BPIDS="$(port_pids "$BACKEND_PORT")"
if [[ -n "$BPIDS" ]]; then
  if curl -fsS --max-time 3 "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    REUSE_BACKEND=true
    log "端口 ${BACKEND_PORT} 上已有健康的后端实例（pid: $(echo "$BPIDS" | tr '\n' ' ')），本次复用，不再重复启动。"
  else
    die "端口 ${BACKEND_PORT} 已被其他进程占用（pid: $(echo "$BPIDS" | tr '\n' ' ')），且不是本应用。
可通过以下任一方式解决后重试：
  1) 结束占用进程：kill \$(lsof -ti tcp:${BACKEND_PORT} -sTCP:LISTEN)
  2) 换端口启动：BACKEND_PORT=8083 ./scripts/start-local.sh"
  fi
fi

if $REUSE_BACKEND; then
  APP_PID="$(port_pids "$BACKEND_PORT" | head -n1)"
else
  log "编译后端（跳过测试）…"
  (cd "$ROOT" && mvn -DskipTests compile) >"$LOG_DIR/mvn-build.log" 2>&1 \
    || { echo "编译失败，日志末尾如下：" >&2; tail -n 30 "$LOG_DIR/mvn-build.log" >&2; die "编译失败，完整日志见 ${LOG_DIR}/mvn-build.log"; }

  log "后台启动 Spring Boot（dev profile，端口 ${BACKEND_PORT}）…"
  (
    cd "$ROOT"
    nohup mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}" \
      </dev/null >"$LOG_DIR/backend.log" 2>&1 &
    echo $! >"$RUN_DIR/backend.pid"
  )
  MVN_PID="$(cat "$RUN_DIR/backend.pid")"

  # 轮询 /actuator/health 直到 UP 或超时；进程提前退出立即失败并给出日志位置。
  DEADLINE=$((SECONDS + WAIT_TIMEOUT_SECS))
  while true; do
    if curl -fsS --max-time 3 "$BACKEND_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      break
    fi
    if ! kill -0 "$MVN_PID" 2>/dev/null; then
      echo "后端进程提前退出，日志末尾如下：" >&2
      tail -n 40 "$LOG_DIR/backend.log" >&2
      die "启动失败，完整日志见 ${LOG_DIR}/backend.log"
    fi
    if (( SECONDS >= DEADLINE )); then
      die "等待 ${WAIT_TIMEOUT_SECS}s 后仍未 UP。查看日志：tail -f ${LOG_DIR}/backend.log"
    fi
    sleep 2
  done
  APP_PID="$(port_pids "$BACKEND_PORT" | head -n1)"
  log "后端已就绪：${BACKEND_URL}/actuator/health（业务 pid: ${APP_PID}，mvn pid: ${MVN_PID}）"
fi

# ---- Nginx 前端容器：无状态，每次按当前端口重建，保证反代上游始终与后端端口一致 ----
FPIDS="$(port_pids "$FRONTEND_PORT")"
if [[ -n "$FPIDS" ]]; then
  OURS_ON_PORT="$(docker ps --filter "name=${NGINX_CONTAINER}" --format '{{.Ports}}' 2>/dev/null | grep -c ":${FRONTEND_PORT}->80" || true)"
  if [[ "${OURS_ON_PORT}" -eq 0 ]]; then
    die "端口 ${FRONTEND_PORT} 已被其他进程占用（pid: $(echo "$FPIDS" | tr '\n' ' ')）。
可换端口重试：FRONTEND_PORT=8093 ./scripts/start-local.sh"
  fi
fi

CONF="$RUN_DIR/nginx-local.conf"
sed "s/host\.docker\.internal:8082/host.docker.internal:${BACKEND_PORT}/g" \
  "$ROOT/deploy/nginx.local.conf" >"$CONF"

docker rm -f "$NGINX_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$NGINX_CONTAINER" \
  -p "${FRONTEND_PORT}:80" \
  --add-host host.docker.internal:host-gateway \
  -v "$ROOT/frontend":/usr/share/nginx/html:ro \
  -v "$CONF":/etc/nginx/conf.d/default.conf:ro \
  nginx:1.27-alpine >/dev/null

# 经由 Nginx 反代验证整条链路（静态页面 + /actuator/health 同源代理）。
DEADLINE=$((SECONDS + 30))
until curl -fsS --max-time 3 "$FRONTEND_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; do
  if (( SECONDS >= DEADLINE )); then
    echo "Nginx 反代自检失败，容器日志末尾如下：" >&2
    docker logs --tail 20 "$NGINX_CONTAINER" >&2 2>&1 || true
    die "请检查 ${CONF} 与端口映射后重试"
  fi
  sleep 1
done
log "Nginx 前端已就绪并完成同源反代自检：${FRONTEND_URL}"

if [[ "${NO_OPEN:-0}" != "1" ]]; then
  if command -v open >/dev/null 2>&1; then
    open "${FRONTEND_URL}/#/chat"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${FRONTEND_URL}/#/chat" >/dev/null 2>&1 || true
  fi
fi

cat <<EOF

============================================================
✅ 本地环境已启动
  前端入口: ${FRONTEND_URL}/#/chat   （Nginx 容器 ${NGINX_CONTAINER}，宿主端口 ${FRONTEND_PORT}）
  后端端口: ${BACKEND_PORT}          （健康检查 ${BACKEND_URL}/actuator/health）
  日志位置: ${LOG_DIR}/backend.log   （构建: mvn-build.log；容器: docker logs -f ${NGINX_CONTAINER}）
  停止命令: ./scripts/stop-local.sh
说明:
  - LLM 真实调用依赖 DASHSCOPE_API_KEY（根目录 .env 或环境变量），当前检测: $( $have_dashscope_key && echo "已配置" || echo "未配置，回答将模板化降级" )
  - RAG 默认 structured + in-memory 向量库，无需 Qdrant；本机 MySQL 未做任何数据改动。
============================================================
EOF
