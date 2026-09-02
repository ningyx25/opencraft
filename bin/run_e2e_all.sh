#!/usr/bin/env bash
# =============================================================================
# OpenCraft natural e2e — 每个任务在固定种子的新生成真实世界里依次运行。
#
# 每个任务独立删档 + 写固定种子 + 启动独立服务器，测试区为自然世界出生点。
# PASS/FAIL 以 run/logs/e2e-results.txt 里该任务的真实结果为准
# （gradle 退出码永远是 0，因为服务器正常跑完就会退出）。
#
# 用法:
#   bash bin/run_e2e_all.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

# 任务列表自动发现：从 e2e/tasks/*.java 的 id() 提取（新增任务无需改本脚本）
E2E_SEED="${E2E_SEED:-opencraft-e2e-2026-09-02-04}"
TASKS=$(./gradlew e2eList -q 2>/dev/null || true)
if [ -z "$TASKS" ]; then
	echo "[E2E] 无法获取任务列表（./gradlew e2eList 失败）"
	exit 1
fi
echo "[E2E] 自然世界种子: $E2E_SEED"
echo "[E2E] 任务列表: $(echo "$TASKS" | tr '\n' ' ')"
PASSED=0
FAILED=0

for task in $TASKS; do
	echo ""
	echo "[E2E] ============ 任务 $task 开始（自然新生成世界）============"
	before=$(wc -l < run/logs/e2e-results.txt 2>/dev/null || echo 0)
	if ./gradlew runE2E -Pe2eTask="$task" -Pe2eSeed="$E2E_SEED" "$@"; then
		after=$(wc -l < run/logs/e2e-results.txt 2>/dev/null || echo 0)
		res=""
		if [ "$after" -gt "$before" ]; then
			res=$(tail -n $((after - before)) run/logs/e2e-results.txt | grep -E "任务结果" | tail -1)
		fi
		case "$res" in
			*": 0/"*) echo "[E2E] FAIL $task"; FAILED=$((FAILED + 1)) ;;
			*通过*) echo "[E2E] PASS $task"; PASSED=$((PASSED + 1)) ;;
			*) echo "[E2E] FAIL $task（无/无法解析结果）"; FAILED=$((FAILED + 1)) ;;
		esac
	else
		echo "[E2E] FAIL $task（gradle 失败）"
		FAILED=$((FAILED + 1))
	fi
done

echo ""
echo "[E2E] ============ 结果 ============"
echo "[E2E] $PASSED/$((PASSED + FAILED)) 通过"
if [ "$FAILED" -gt 0 ]; then
	echo "[E2E] 失败: $FAILED"
	exit 1
fi