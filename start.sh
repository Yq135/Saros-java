#!/usr/bin/env bash
# Saros 后端后台启动脚本：读 src/main/resources/.env 环境变量 → 打包（如需要）→ nohup 启动 → 健康检查
set -euo pipefail

cd "$(dirname "$0")"

ENV_FILE="src/main/resources/.env"
PID_FILE=".saros.pid"
LOG_DIR="logs"
HEALTH_URL="http://localhost:8080/api/health"

usage() {
    echo "用法: ./start.sh [-b|--build]   # -b 强制重新打包"
    exit 1
}

BUILD=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        -b|--build) BUILD=true ;;
        *) usage ;;
    esac
    shift
done

# 1. 读环境变量
if [[ ! -f "$ENV_FILE" ]]; then
    echo "错误: 未找到 $ENV_FILE" >&2
    exit 1
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# 2. 已在运行则拒绝重复启动
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Saros 已在运行 (PID $(cat "$PID_FILE"))，如需重启请先 ./stop.sh"
    exit 0
fi

# 3. 打包（无 jar 或显式 -b）
JAR=$(ls -t target/saros-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" || "$BUILD" == true ]]; then
    echo ">>> 打包（-DskipTests）..."
    ./mvnw -q package -DskipTests
    JAR=$(ls -t target/saros-*.jar | head -1)
fi

# 4. 后台启动（--enable-preview 与 pom 编译参数一致）
# 标准输出重定向到 console.log；应用日志由 logback 滚动写入 logs/saros.log（两文件不双写）
mkdir -p "$LOG_DIR"
nohup java --enable-preview -jar "$JAR" > "$LOG_DIR/console.log" 2>&1 &
echo $! > "$PID_FILE"
echo ">>> Saros 启动中 (PID $(cat "$PID_FILE"))，日志: $LOG_DIR/saros.log（启动控制台: $LOG_DIR/console.log）"

# 5. 健康检查（最多 60s；进程提前退出则报错）
for _ in $(seq 1 60); do
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
        echo ">>> 启动成功，健康检查通过: $HEALTH_URL"
        exit 0
    fi
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo ">>> 启动失败，进程已退出。查看日志: tail -50 $LOG_DIR/console.log" >&2
        exit 1
    fi
    sleep 1
done
echo ">>> 60s 内健康检查未通过（可能 PG 不可达），进程仍在运行。查看日志: $LOG_DIR/console.log / $LOG_DIR/saros.log" >&2
exit 1
