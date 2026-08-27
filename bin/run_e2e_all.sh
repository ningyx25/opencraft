#!/usr/bin/env bash
# =============================================================================
# OpenCraft e2e — 全部 5 个任务，每个任务在全新世界里依次跑。
#
# 等价于依次执行：
#   ./gradlew runE2E -Pe2eTask=chop_tree
#   ./gradlew runE2E -Pe2eTask=place_workbench
#   ...
# 每个任务独立删档（cleanE2EWorld）+ 独立服务器 → 单任务语义。
# PASS/FAIL 以 run/logs/e2e-results.txt 里该任务套件的真实结果为准
# （gradle 退出码永远是 0，因为服务器正常跑完就会退出）。
#
# 用法:
#   bash bin/run_e2e_all.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

TASKS="chop_tree place_workbench craft_wooden_pickaxe craft_stone_pickaxe mine_stone"
PASSED=0
FAILED=0

for task in $TASKS; do
	echo ""
	echo "[E2E] ============ 任务 $task 开始（全新世界）============"
	before=$(wc -l < run/logs/e2e-results.txt 2>/dev/null || echo 0)
	if ./gradlew runE2E -Pe2eTask="$task" "$@"; then
		after=$(wc -l < run/logs/e2e-results.txt 2>/dev/null || echo 0)
		res=""
		if [ "$after" -gt "$before" ]; then
			res=$(tail -n $((after - before)) run/logs/e2e-results.txt | grep -E "套件结果" | tail -1)
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