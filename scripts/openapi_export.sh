#!/usr/bin/env bash
# 从阶段二（Saros-python）导出 OpenAPI 契约基线 → docs/openapi-phase2.json
# ① 优先抓运行中的 FastAPI 服务（SAROS_BACKEND_URL，默认 127.0.0.1:8000）
# ② 失败则离线 import（自动找 conda saros 环境，可用 SAROS_PYTHON 覆盖 Python 路径）
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$SCRIPT_DIR/../docs/openapi-phase2.json}"
BASE_URL="${SAROS_BACKEND_URL:-http://127.0.0.1:8000}"

if curl -sf --max-time 5 "$BASE_URL/openapi.json" > "$OUT.tmp"; then
    echo "✅ 从运行中的 FastAPI 服务导出（$BASE_URL）→ $OUT"
else
    PYTHON=""
    for cand in "${SAROS_PYTHON:-}" "$HOME/anaconda3/envs/saros/bin/python3" "python3"; do
        [ -n "$cand" ] || continue
        if command -v "$cand" >/dev/null 2>&1; then PYTHON="$cand"; break; fi
    done
    PY_BACKEND="$SCRIPT_DIR/../../Saros-python/backend"
    echo "${BASE_URL} 无服务，尝试离线 import：${PY_BACKEND}（Python: ${PYTHON}）"
    (cd "$PY_BACKEND" && "$PYTHON" -c \
        "from app.main import app; import json; print(json.dumps(app.openapi(), ensure_ascii=False, indent=2))") > "$OUT.tmp"
    echo "✅ 离线导出 → $OUT"
fi
mv "$OUT.tmp" "$OUT"
python3 -c "import json;d=json.load(open('$OUT'));print('端点数量:', len(d['paths']));print('\n'.join(sorted(d['paths'].keys())))"
