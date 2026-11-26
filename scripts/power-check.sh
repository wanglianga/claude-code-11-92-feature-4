#!/usr/bin/env bash
# feature-4 馈线降容供电调度 —— 一键验证脚本
# 登录 dispatch → POST /api/replays/power（重置样例并跑 23 条断言：
# 两种顺序的精确分配 / 馈线总量核验 / 受影响货物 / 削减补偿 / 恢复次序 /
# 撤销生成新方案且旧回执逐字不变 / DB 触发器阻止改删回执）。
# 用法：bash scripts/power-check.sh [BASE_URL]
set -u

BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
USERNAME="${DISPATCH_USER:-dispatch}"
PASSWORD="${DISPATCH_PASS:-dispatch123}"

echo "==> 目标服务: $BASE"

LOGIN_JSON=$(curl -sS --max-time 10 -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_JSON" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
if [ -z "$TOKEN" ]; then
  echo "登录失败：$LOGIN_JSON" >&2
  exit 2
fi
echo "==> 登录成功，执行供电调度自检..."

TMP=$(mktemp -t pw-check-XXXXXX.json)
trap 'rm -f "$TMP"' EXIT
HTTP=$(curl -sS --max-time 120 -X POST "$BASE/api/replays/power" \
  -H "Authorization: Bearer $TOKEN" -o "$TMP" -w '%{http_code}')
echo "==> POST /api/replays/power -> HTTP $HTTP"

python3 - "$TMP" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    d = json.load(f)
checks = d.get("checks", [])
failed = [c for c in checks if not c.get("pass")]
print(f"==> 供电断言：{len(checks)-len(failed)}/{len(checks)} 通过；allPassed={d.get('allPassed')}")
for c in failed:
    print("  FAIL:", c.get("name"))
    print("    expected:", c.get("expected"), "actual:", c.get("actual"))
if d.get("allPassed") is True and not failed:
    print("ALL_POWER_CHECKS_PASSED")
    sys.exit(0)
print("POWER_CHECKS_FAILED")
sys.exit(1)
PY
CODE=$?
if [ "$HTTP" != "200" ]; then
  echo "接口返回非 200，响应前 500 字：" >&2
  head -c 500 "$TMP" >&2
  exit 3
fi
exit $CODE
