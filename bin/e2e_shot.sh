#!/usr/bin/env bash
# =============================================================================
# OpenCraft e2e 运行 + 服务端 Replay 录制
#
# 默认（无头模式）：只跑 e2e 服务器，服务器内用 spectator 录制假玩家把全部
# 客户端包录成 ReplayMod .mcpr 回放，同时产出结构化事件时间线 JSONL：
#   - run/replays/e2e-<任务>-<时间>.mcpr   ReplayMod 回放（自由相机/第三人称/4K 反复渲染）
#   - run/logs/e2e-<任务>-<时间>.jsonl     事件时间线（工具调用参数/结果/背包/成败）
# 不需要 Xvfb、ffmpeg、真客户端或游戏资源，跑完服务器自动退出。
#
# 可选真客户端模式（实时围观/老式第一人称 mp4 录屏）：
#   E2E_CLIENT=1   起 Xvfb + 真客户端 + ffmpeg 录 mp4（与 .mcpr 同时产出）
#   E2E_LIVE_PORT=<端口>  起 Xvfb + 真客户端，ffmpeg 推 mpegts-over-TCP 直播流
#     本机看:  ffplay tcp://127.0.0.1:$E2E_LIVE_PORT
#     SSH 看:  ssh -L $E2E_LIVE_PORT:127.0.0.1:$E2E_LIVE_PORT user@host
#              然后本地 ffplay tcp://127.0.0.1:$E2E_LIVE_PORT
#
# 用法:
#   bin/e2e_shot.sh <task>
#     task   e2e 任务 id（必填，如 chop_tree / craft_furnace）
# 环境变量:
#   E2E_HOLD_MS     任务后服务器保持毫秒（客户端模式默认 120000，无头模式默认 0）
#   E2E_CLIENT      1 = 起真客户端 + 录 mp4（默认 0，无头只产 .mcpr）
#   E2E_VIDEO       0 = 客户端模式也不录 mp4（默认录）
#   E2E_FPS         录屏帧率（默认 10）
#   E2E_LIVE_PORT   设置后改推 TCP 直播流（不录 mp4），隐含 E2E_CLIENT
#   E2E_BASE_URL    透传给 runE2E 的 -Pe2eBaseUrl（如 http://127.0.0.1:18923/v1 指向 mock，不烧真实 LLM）
#   E2E_RENDER       1 = e2e 跑完后自动用 ReplayMod 无头渲染最新 mcpr -> mp4（需 ffmpeg，软渲染较慢）
#   RENDER_W/RENDER_H/RENDER_FPS   E2E_RENDER 时的分辨率/帧率（默认 1280/720/20）
#
# 客户端模式需要: xvfb、mesa-utils（llvmpipe）、ffmpeg（x11grab）、游戏资源已下载
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

TASK="${1:-}"
DISPLAY_NUM="${DISPLAY_NUM:-99}"
PORT="${PORT:-25565}"
SCREEN="1280x720x24"
FPS="${E2E_FPS:-10}"
STAMP_WAIT=0

if [ -z "$TASK" ]; then
	echo "[shot] 用法: bin/e2e_shot.sh <task>（例如 chop_tree）"
	exit 2
fi

# 真客户端模式：E2E_LIVE_PORT 隐含开启
if [ -n "${E2E_LIVE_PORT:-}" ] || [ "${E2E_CLIENT:-0}" = "1" ]; then
	DO_CLIENT=1
else
	DO_CLIENT=0
fi
DO_VIDEO="${E2E_VIDEO:-1}"
# hold：客户端模式等客户端连入默认 120s；无头模式跑完即退
if [ -n "${E2E_HOLD_MS:-}" ]; then
	HOLD_MS="$E2E_HOLD_MS"
else
	HOLD_MS=$([ "$DO_CLIENT" = "1" ] && echo 120000 || echo 0)
fi

XVFB_PID=""
FFMPEG_PID=""
CLIENT_PID=""
SERVER_PID=""
cleanup() {
	# 客户端 JVM：kill 掉 gradle 包装进程不会杀掉 fork 出的真客户端 JVM
	# （命令行带 -Dfabric.dli.env=client），用 pkill 按特征杀（本脚本自身命令行不含该串，安全）
	pkill -f 'fabric\.dli\.env=client' 2>/dev/null || true
	# ffmpeg 先 SIGINT 收尾（写 moov atom 让 mp4 可播放），1s 不退则 SIGKILL 兜底
	if [ -n "$FFMPEG_PID" ]; then
		kill -INT "$FFMPEG_PID" 2>/dev/null
		sleep 1
		kill -KILL "$FFMPEG_PID" 2>/dev/null
		wait "$FFMPEG_PID" 2>/dev/null
	fi
	kill "${CLIENT_PID:-}" 2>/dev/null || true
	kill "${XVFB_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

# 1) 客户端模式：起虚拟显示 + 录屏/直播
if [ "$DO_CLIENT" = "1" ]; then
	Xvfb ":$DISPLAY_NUM" -screen 0 "$SCREEN" >/tmp/e2e-xvfb.log 2>&1 &
	XVFB_PID=$!
	sleep 1
	export DISPLAY=":$DISPLAY_NUM"

	if [ "$DO_VIDEO" != "0" ] && command -v ffmpeg >/dev/null 2>&1; then
		VID_W="$(echo "$SCREEN" | cut -dx -f1)"
		VID_H="$(echo "$SCREEN" | cut -dx -f2)"
		if [ -n "${E2E_LIVE_PORT:-}" ]; then
			# 直播模式：mpegts-over-TCP（SSH -L 友好——TCP 隧道，普通 ssh -L 即可转发；
			# 不另录 mp4：同一 Xvfb 不能被两个 x11grab 同时抓，且单路 `?listen=1` 只接受一个查看者）
			echo "[shot] 开始实时直播 → tcp://127.0.0.1:$E2E_LIVE_PORT"
			ffmpeg -y -f x11grab -framerate "$FPS" -video_size "${VID_W}x${VID_H}" -i ":$DISPLAY_NUM" \
				-c:v libx264 -preset ultrafast -crf 28 -f mpegts "tcp://127.0.0.1:$E2E_LIVE_PORT?listen=1" \
				>/tmp/e2e-ffmpeg.log 2>&1 &
			FFMPEG_PID=$!
			echo "[shot]  本机看: ffplay tcp://127.0.0.1:$E2E_LIVE_PORT"
			echo "[shot]  SSH 看: ssh -L $E2E_LIVE_PORT:127.0.0.1:$E2E_LIVE_PORT user@host  然后本地 ffplay tcp://127.0.0.1:\$E2E_LIVE_PORT"
		else
			# 录制模式：mp4
			OUTDIR="run/screenshots"
			mkdir -p "$OUTDIR"
			OUT="$(pwd)/$OUTDIR/e2e-$TASK-$(date +%H%M%S).mp4"
			echo "[shot] 开始实时录屏 → $OUT"
			ffmpeg -y -f x11grab -framerate "$FPS" -video_size "${VID_W}x${VID_H}" -i ":$DISPLAY_NUM" \
				-c:v libx264 -preset ultrafast -crf 28 -pix_fmt yuv420p -movflags +faststart \
				"$OUT" >/tmp/e2e-ffmpeg.log 2>&1 &
			FFMPEG_PID=$!
		fi
	fi
else
	echo "[shot] 无头模式：服务端录制 Replay（.mcpr）+ 事件时间线（.jsonl），不启动真客户端"
fi

# 2) 起 e2e 服务器（无头，后台；自动删 run/world，用固定种子重新生成自然世界）
BASE_URL_ARG=""
[ -n "${E2E_BASE_URL:-}" ] && BASE_URL_ARG="-Pe2eBaseUrl=$E2E_BASE_URL"
HOLD_ARG=""
[ "$HOLD_MS" != "0" ] && HOLD_ARG="-Pe2eHoldMs=$HOLD_MS"
./gradlew runE2E -Pe2eTask="$TASK" $HOLD_ARG -Pe2eSeed="${E2E_SEED:-opencraft-e2e-2026-09-02-04}" $BASE_URL_ARG >/tmp/e2e-server.log 2>&1 &
SERVER_PID=$!

# 3) 客户端模式：等端口就绪后起真客户端（软渲染 + quickPlay 自动进服）
if [ "$DO_CLIENT" = "1" ]; then
	echo "[shot] 等待服务器 127.0.0.1:$PORT 就绪…"
	for i in $(seq 1 60); do
		if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
			exec 3>&- 3<&-
			echo "[shot] 服务器就绪（${i}s）"
			break
		fi
		sleep 1
	done

	echo "[shot] 启动真客户端（Xvfb + llvmpipe 软渲染）…"
	LIBGL_ALWAYS_SOFTWARE=1 \
	./gradlew runClient --args="--quickPlayMultiplayer 127.0.0.1:$PORT" >/tmp/e2e-client.log 2>&1 &
	CLIENT_PID=$!

	if [ -n "${E2E_LIVE_PORT:-}" ]; then
		echo "[shot] 直播流就绪: 本机 ffplay tcp://127.0.0.1:$E2E_LIVE_PORT ；SSH: ssh -L $E2E_LIVE_PORT:127.0.0.1:$E2E_LIVE_PORT user@host 后本地 ffplay tcp://127.0.0.1:$E2E_LIVE_PORT"
	fi
fi

# 4) 等服务器跑完（无头模式跑完即退；客户端模式含 hold 时间）
echo "[shot] 等 e2e 服务器结束…"
wait "$SERVER_PID"
echo "[shot] e2e 服务器已结束"

# 5) 收尾：停客户端（含 fork 出的 JVM），等 ffmpeg 收尾
if [ "$DO_CLIENT" = "1" ]; then
	kill "${CLIENT_PID:-}" 2>/dev/null || true
	pkill -f 'fabric\.dli\.env=client' 2>/dev/null || true
	sleep 2
fi

# 6) 报告产物
echo "[shot] 完成！"
LATEST_MCPR="$(ls -t run/replays/e2e-"$TASK"-*.mcpr 2>/dev/null | head -1)"
LATEST_JSONL="$(ls -t run/logs/e2e-"$TASK"-*.jsonl 2>/dev/null | head -1)"
if [ -n "$LATEST_MCPR" ]; then
	echo "  Replay 回放: $(pwd)/$LATEST_MCPR"
	echo "    （用 ReplayMod 打开：自由相机/第三人称，可反复渲染 mp4）"
	ls -lh "$LATEST_MCPR" | tail -1
	if [ "${E2E_RENDER:-0}" = "1" ]; then
		echo "[shot] E2E_RENDER=1：开始无头渲染 mp4（软渲染需要一些时间）…"
		RENDER_W="${RENDER_W:-1280}" RENDER_H="${RENDER_H:-720}" RENDER_FPS="${RENDER_FPS:-20}" \
			./bin/render_replay.sh "$LATEST_MCPR" || echo "[shot] 渲染失败（详见上方 render 输出）"
	fi
fi
if [ -n "$LATEST_JSONL" ]; then
	echo "  事件时间线: $(pwd)/$LATEST_JSONL"
fi
if [ "$DO_CLIENT" = "1" ] && [ -z "${E2E_LIVE_PORT:-}" ] && [ "$DO_VIDEO" != "0" ]; then
	ls -lh run/screenshots/e2e-"$TASK"-*.mp4 2>/dev/null | tail -1
fi
if [ -n "${E2E_LIVE_PORT:-}" ]; then
	echo "  直播流: tcp://127.0.0.1:$E2E_LIVE_PORT（已随服务器结束关闭）"
fi
