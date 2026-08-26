#!/usr/bin/env bash
# =============================================================================
# OpenCraft e2e + 真客户端第一人称视频
#
# 在无头环境跑一轮 e2e，同时用 Xvfb + 软渲染跑一个真 Minecraft 客户端：
# 服务器每 tick 把客户端玩家粘到 AI 助手眼睛位置/朝向，产出：
#   - run/screenshots/e2e-<任务>.mp4  实时录屏（ffmpeg x11grab 抓 Xvfb，边跑边录）
# 可选实时直播：设置 E2E_LIVE_PORT 后 ffmpeg 改为推 mpegts-over-TCP 流（TCP，SSH 友好）：
#   本机看:  ffplay tcp://127.0.0.1:$E2E_LIVE_PORT
#   SSH 看:  ssh -L $E2E_LIVE_PORT:127.0.0.1:$E2E_LIVE_PORT user@host
#            然后本地 ffplay tcp://127.0.0.1:$E2E_LIVE_PORT
#
# 用法:
#   bin/e2e_shot.sh [task] [interval_sec_ignored]
#     task          e2e 任务 id（默认 all；如 mine_stone / chop_tree）
#     interval_sec  保留兼容、无效果（有实时视频了，不再逐帧截图省内存）
# 环境变量:
#   E2E_HOLD_MS     任务后服务器保持毫秒（默认 120000）
#   E2E_VIDEO       0 = 不录视频（默认录）
#   E2E_FPS         录屏帧率（默认 10）
#   E2E_LIVE_PORT   设置后额外推一路 UDP 直播流
#   E2E_BASE_URL    透传给 runE2E 的 -Pe2eBaseUrl（如 http://127.0.0.1:18923/v1 指向 mock，不烧真实 LLM）
#
# 需要: xvfb、mesa-utils（llvmpipe）、ffmpeg（x11grab）、游戏资源已下载
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

TASK="${1:-all}"
INTERVAL="${2:-5}"   # 兼容占位：不再逐帧截图
DISPLAY_NUM="${DISPLAY_NUM:-99}"
PORT="${PORT:-25565}"
SCREEN="1280x720x24"
HOLD_MS="${E2E_HOLD_MS:-120000}"
DO_VIDEO="${E2E_VIDEO:-1}"
FPS="${E2E_FPS:-10}"
LIVE_PORT="${E2E_LIVE_PORT:-}"
STAMP="$(date +%H%M%S)"

echo "[shot] 任务=$TASK  显示=:$DISPLAY_NUM  hold=${HOLD_MS}ms  视频=${DO_VIDEO}  fps=${FPS}  直播端口=${LIVE_PORT:-无}"

# 1) 起虚拟显示
Xvfb ":$DISPLAY_NUM" -screen 0 "$SCREEN" >/tmp/e2e-xvfb.log 2>&1 &
XVFB_PID=$!
sleep 1

FFMPEG_PID=""
cleanup() {
	# 客户端 JVM：kill 掉 gradle 包装进程不会杀掉 fork 出的真客户端 JVM
	# （命令行带 -Dfabric.dli.env=client），用 pkill 按特征杀（本脚本自身命令行不含该串，安全）
	pkill -f 'fabric\.dli\.env' 2>/dev/null || true
	# ffmpeg 先 SIGINT 收尾（写 moov atom 让 mp4 可播放），1s 不退则 SIGKILL 兜底
	if [ -n "$FFMPEG_PID" ]; then
		kill -INT "$FFMPEG_PID" 2>/dev/null
		sleep 1
		kill -KILL "$FFMPEG_PID" 2>/dev/null
		wait "$FFMPEG_PID" 2>/dev/null
	fi
	kill "${CLIENT_PID:-}" 2>/dev/null || true
	kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

# 1.5) 实时录屏（x11grab 抓整个 :99 屏；客户端窗口 854x480 居中，四周黑边可自行 crop）
if [ "$DO_VIDEO" != "0" ] && command -v ffmpeg >/dev/null 2>&1; then
	VID_W="$(echo "$SCREEN" | cut -dx -f1)"
	VID_H="$(echo "$SCREEN" | cut -dx -f2)"
	if [ -n "$LIVE_PORT" ]; then
		# 直播模式：mpegts-over-TCP（SSH -L 友好——TCP 隧道，普通 ssh -L 即可转发；
		# 不另录 mp4：同一 Xvfb 不能被两个 x11grab 同时抓，且单路 `?listen=1` 只接受一个查看者）
		echo "[shot] 开始实时直播 → tcp://127.0.0.1:$LIVE_PORT"
		ffmpeg -y -f x11grab -framerate "$FPS" -video_size "${VID_W}x${VID_H}" -i ":$DISPLAY_NUM" \
			-c:v libx264 -preset ultrafast -crf 28 -f mpegts "tcp://127.0.0.1:$LIVE_PORT?listen=1" \
			>/tmp/e2e-ffmpeg.log 2>&1 &
		FFMPEG_PID=$!
		echo "[shot]  本机看: ffplay tcp://127.0.0.1:$LIVE_PORT"
		echo "[shot]  SSH 看: ssh -L $LIVE_PORT:127.0.0.1:$LIVE_PORT user@host  然后本地 ffplay tcp://127.0.0.1:\$LIVE_PORT"
	else
		# 录制模式：mp4
		OUTDIR="run/screenshots"
		mkdir -p "$OUTDIR"
		OUT="$(pwd)/$OUTDIR/e2e-$TASK-$STAMP.mp4"
		echo "[shot] 开始实时录屏 → $OUT"
		ffmpeg -y -f x11grab -framerate "$FPS" -video_size "${VID_W}x${VID_H}" -i ":$DISPLAY_NUM" \
			-c:v libx264 -preset ultrafast -crf 28 -pix_fmt yuv420p -movflags +faststart \
			"$OUT" >/tmp/e2e-ffmpeg.log 2>&1 &
		FFMPEG_PID=$!
	fi
fi

# 2) 起 e2e 服务器（无头，后台；自动删 run/world 拿全新存档 + 写 spawn-protection=0 / online-mode=false）
BASE_URL_ARG=""
[ -n "${E2E_BASE_URL:-}" ] && BASE_URL_ARG="-Pe2eBaseUrl=$E2E_BASE_URL"
export DISPLAY=":$DISPLAY_NUM"
./gradlew runE2E -Pe2eTask="$TASK" -Pe2eHoldMs="$HOLD_MS" $BASE_URL_ARG >/tmp/e2e-server.log 2>&1 &
SERVER_PID=$!

# 3) 等服务器端口就绪
echo "[shot] 等待服务器 127.0.0.1:$PORT 就绪…"
for i in $(seq 1 60); do
	if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
		exec 3>&- 3<&-
		echo "[shot] 服务器就绪（${i}s）"
		break
	fi
	sleep 1
done

# 4) 起真客户端（软渲染 + quickPlay 自动进服）——服务器 hold 期间连入被粘到助手眼睛；
#    不设 OPEN_CRAFT_SHOT_AUTOCAPTURE（有实时视频了，不再逐帧截图，省内存/磁盘）
echo "[shot] 启动真客户端（Xvfb + llvmpipe 软渲染）…"
LIBGL_ALWAYS_SOFTWARE=1 \
./gradlew runClient --args="--quickPlayMultiplayer 127.0.0.1:$PORT" >/tmp/e2e-client.log 2>&1 &
CLIENT_PID=$!

if [ -n "$LIVE_PORT" ]; then
	echo "[shot] 直播流就绪: 本机 ffplay tcp://127.0.0.1:$LIVE_PORT ；SSH: ssh -L $LIVE_PORT:127.0.0.1:$LIVE_PORT user@host 后本地 ffplay tcp://127.0.0.1:$LIVE_PORT"
fi

# 5) 等服务器跑完（含 hold 时间）
echo "[shot] 等 e2e 服务器结束（含 hold）…"
wait "$SERVER_PID"
echo "[shot] e2e 服务器已结束"

# 6) 收尾：停客户端（含 fork 出的 JVM），等 ffmpeg 收尾，报告
kill "${CLIENT_PID:-}" 2>/dev/null || true
pkill -f 'fabric\.dli\.env' 2>/dev/null || true
sleep 2
echo "[shot] 完成！"
if [ -n "$FFMPEG_PID" ]; then
	if [ -n "$LIVE_PORT" ]; then
		echo "  直播流: tcp://127.0.0.1:$LIVE_PORT（已随服务器结束关闭）"
	else
		echo "  视频: $(pwd)/run/screenshots/e2e-$TASK-$STAMP.mp4"
		ls -lh run/screenshots/e2e-$TASK-$STAMP.mp4 2>/dev/null | tail -1
	fi
fi
