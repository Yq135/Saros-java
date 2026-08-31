#!/usr/bin/env bash
# Saros 后端关停脚本：按 PID 文件优雅停止（超时强杀）；无 PID 文件时按进程特征兜底匹配
set -euo pipefail

cd "$(dirname "$0")"

PID_FILE=".saros.pid"

if [[ -f "$PID_FILE" ]]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo ">>> 停止 Saros (PID $PID)..."
        kill "$PID"
        for _ in $(seq 1 30); do
            if ! kill -0 "$PID" 2>/dev/null; then
                rm -f "$PID_FILE"
                echo ">>> 已停止"
                exit 0
            fi
            sleep 1
        done
        echo ">>> 优雅停止超时（30s），强制结束" >&2
        kill -9 "$PID" 2>/dev/null || true
    else
        echo ">>> PID 文件存在但进程已不在，清理"
    fi
    rm -f "$PID_FILE"
    exit 0
fi

# 兜底：无 PID 文件，按 jar 特征匹配进程
PIDS=$(pgrep -f "saros-.*\.jar" || true)
if [[ -n "$PIDS" ]]; then
    echo ">>> 未找到 PID 文件，按进程匹配停止: $PIDS"
    # shellcheck disable=SC2086
    kill $PIDS
else
    echo ">>> Saros 未在运行"
fi
