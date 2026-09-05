package com.swaydy.opencraft.renderer;

import com.replaymod.core.ReplayMod;
import com.replaymod.render.RenderSettings;
import com.replaymod.render.rendering.VideoRenderer;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.simplepathing.SPTimeline;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 开发期无头渲染自动化（不随生产 jar 发布）：
 * 启动参数带 {@code -Dopencraft.render.replay=<file.mcpr>} 时，等待 ReplayMod 就绪后
 * 自动加载回放 → 每客户端 tick 喂回放包直到世界加载（等价于 ReplayMod 回放视图的
 * InputReplayTimer 驱动，不能在主线程同步灌包——配置阶段 handler 要等主线程 tick）→
 * 安装第三人称追尾相机 → 调 ReplayMod 的 VideoRenderer 离屏渲染 mp4 → 写完成标记并
 * 退出游戏。全程无人参与，由 bin/render_replay.sh 在 Xvfb 下驱动。
 */
public class RenderAutomation implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("opencraft-renderer");

	private boolean started;
	private ReplayHandler handler;
	private ReplayFile replayFile;
	private File out;
	private ReplaySender sender;
	private int fedToMs;
	/** 世界加载完成时的回放时间(ms)；默认从此开始渲染，避免回卷重建相机导致控制器丢失。 */
	private int loadedAtMs = -1;
	private boolean rendering;

	@Override
	public void onInitializeClient() {
		String replayPath = System.getProperty("opencraft.render.replay", "").trim();
		if (replayPath.isEmpty()) {
			return; // 普通开发客户端：完全 no-op
		}
		LOGGER.info("[renderer] 自动渲染模式：{}", replayPath);
		// ReplayMod 自身的 client entrypoint 初始化顺序不保证，用 tick 轮询它就绪。
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (started) {
				return;
			}
			if (ReplayMod.instance == null || ReplayModReplay.instance == null) {
				return;
			}
			// 必须等主菜单 TitleScreen 首次初始化完成后才能开回放：GuiHandler 在每个屏幕
			// init 时都会 ensureReplayStopped（若当前是 Title/JoinMP 且已有回放就结束它）。
			// 若在游戏还在加载时就开回放，随后 TitleScreen 的首次 init 会立刻把回放关掉。
			if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
				return;
			}
			started = true;
			ReplayMod.instance.runLaterWithoutLock(() -> openReplay(mc, replayPath));
		});
	}

	private void openReplay(Minecraft mc, String replayPath) {
		File mcpr = new File(replayPath);
		out = resolveOutput(mcpr);
		LOGGER.info("[renderer] 开始渲染 {} → {}", mcpr.getAbsolutePath(), out.getAbsolutePath());
		Throwable failure = null;
		try {
			if (!mcpr.isFile()) {
				throw new IOException("回放文件不存在: " + mcpr.getAbsolutePath());
			}
			File parent = out.getParentFile();
			if (parent != null) {
				Files.createDirectories(parent.toPath());
			}
			Files.deleteIfExists(markerFile(out).toPath());

			replayFile = ReplayMod.instance.files.open(mcpr.toPath());
			// 关键：GuiHandler.ensureReplayStopped 会在 TitleScreen/JoinMultiplayerScreen 初始化时
			// 结束当前回放；若从主菜单直接开回放，标题屏的后续 init 会立刻把回放关掉（数据流终止）。
			// 先关掉标题屏（等价于 ReplayMod 从 ReplayViewer 点 Load 后关闭当前 GUI），再开回放。
			mc.setScreen(null);
			// EVENT.register 是包私有的；改用轮询 FullReplaySender.terminate 标志来观察关闭时机。
			handler = ReplayModReplay.instance.startReplay(replayFile, true, true);
			if (handler == null) {
				throw new IOException("startReplay 返回 null（回放加载失败，缺 mod 或文件损坏？）");
			}
			sender = handler.getReplaySender();
			LOGGER.info("[renderer] replay duration={}ms", handler.getReplayDuration());
			// 每 tick 喂 1 秒回放包，直到世界加载完成。
			ClientTickEvents.END_CLIENT_TICK.register(this::tickFeed);
		} catch (Throwable t) {
			failure = t;
			LOGGER.error("[renderer] 打开回放失败", t);
			finish(mc, failure);
		}
	}

	private int waitTicks;

	/** 每客户端 tick：等回放世界加载（async 模式由 ReplayMod 定时器实时喂包），然后切到渲染。 */
	private void tickFeed(Minecraft mc) {
		if (rendering || handler == null) {
			return;
		}
		int now = sender.currentTimeStamp();
		fedToMs = Math.max(fedToMs, now);
		waitTicks++;
		// 配置/登录阶段卡住（30 秒内没到 play 初始区）或已到 3s 仍无世界 → 诊断/失败
		if (mc.level == null && waitTicks > 30 * 20 && now < 3000) {
			LOGGER.error("[renderer] 回放推进停滞（senderTime={}ms）", now);
			finish(mc, new IOException("回放推进停滞: senderTime=" + now + "ms"));
			return;
		}
		if (mc.level == null && now >= 3000 && waitTicks > 30 * 20 + 100) {
			LOGGER.error("[renderer] 已到 play 但世界未加载（senderTime={}ms conn={}）", now,
					mc.getConnection() == null ? "null" : mc.getConnection().getClass().getSimpleName());
			finish(mc, new IOException("回放世界加载失败: senderTime=" + now + "ms"));
			return;
		}
		if (mc.level != null) {
			if (loadedAtMs < 0) {
				loadedAtMs = fedToMs;
				LOGGER.info("[renderer] 世界在回放 {}ms 加载完成", loadedAtMs);
			}
			if (mc.level == null) {
				LOGGER.error("[renderer] 回放包已喂完但世界未加载（fedTo={}ms）conn={} senderTime={}",
						fedToMs,
						mc.getConnection() == null ? "null" : mc.getConnection().getClass().getSimpleName(),
						sender.currentTimeStamp());
				finish(mc, new IOException("回放世界加载失败（mcpr 损坏？fedTo=" + fedToMs + "ms）"));
				return;
			}
			rendering = true;
			LOGGER.info("[renderer] 世界已加载，开始渲染");
			ReplayMod.instance.runLaterWithoutLock(() -> render(mc));
		}
	}

	private void render(Minecraft mc) {
		Throwable failure = null;
		try {
			// 第三人称追尾相机
			String follow = System.getProperty("opencraft.render.follow", "").trim();
			var camEntity = handler.getCameraEntity();
			LOGGER.info("[renderer] cameraEntity={} currentController={} mc.playerIsCam={}",
					camEntity.getClass().getSimpleName(),
					camEntity.getCameraController() == null ? "null"
							: camEntity.getCameraController().getClass().getSimpleName(),
					mc.player == camEntity);
			camEntity.setCameraController(
					new FollowChaseController(camEntity, follow));
			LOGGER.info("[renderer] controller 已设为 FollowChaseController");

			// 时间线：time keyframe 决定总帧数与回放推进（1:1 实时）；
			// 不加 position keyframe，相机完全由我方控制器驱动。
			// 可选 -Dopencraft.render.from/to（毫秒）只渲染一段，便于诊断/快速预览。
			int duration = handler.getReplayDuration();
			// gradle runClient 默认会传 -Dopencraft.render.from=0/-to=0，0 视为“未指定”
			int fromProp = Integer.getInteger("opencraft.render.from", 0);
			int toProp = Integer.getInteger("opencraft.render.to", 0);
			// 至少从 5s 开始：回放开头是登录/配置/进服，ReplayMod 在 self 玩家就位前不会逐帧 tick 世界
			// （hasWorldLoaded 未置位 → CameraEntity.tick/追尾控制器不执行），从过早就只有静止镜头。
			int fromMs = fromProp > 0 ? fromProp : (loadedAtMs > 0 ? Math.max(loadedAtMs, 5000) : 5000);
			int toMs = toProp > 0 ? toProp : duration;
			fromMs = Math.min(fromMs, Math.max(duration - 1, 0));
			toMs = Math.max(toMs, fromMs + 1);
			LOGGER.info("[renderer] timeline from={}ms to={}ms (duration={}ms)", fromMs, toMs, duration);
			SPTimeline sp = new SPTimeline();
			sp.addTimeKeyframe(0, fromMs);
			sp.addTimeKeyframe(toMs - fromMs, toMs);

			RenderSettings settings = buildSettings(out);
			VideoRenderer renderer = new VideoRenderer(settings, handler, sp.getTimeline());
			boolean completed = renderer.renderVideo();
			if (!completed) {
				throw new IOException("渲染被取消（窗口关闭？）");
			}
			LOGGER.info("[renderer] 渲染完成：{}", out.getAbsolutePath());
		} catch (Throwable t) {
			failure = t;
			LOGGER.error("[renderer] 回放渲染失败", t);
		} finally {
			finish(mc, failure);
		}
	}

	private void finish(Minecraft mc, Throwable failure) {
		try {
			writeMarker(out, failure);
		} catch (Throwable ignored) {
		}
		try {
			if (handler != null) {
				handler.endReplay();
			}
		} catch (Throwable ignored) {
		}
		mc.stop();
	}

	private static File resolveOutput(File mcpr) {
		String outProp = System.getProperty("opencraft.render.out", "").trim();
		if (!outProp.isEmpty()) {
			return new File(outProp);
		}
		String name = mcpr.getName().replaceAll("(?i)\\.mcpr$", "") + ".mp4";
		return new File("renderings", name);
	}

	private static File markerFile(File out) {
		return new File(out.getParentFile(), out.getName() + ".done");
	}

	private static void writeMarker(File out, Throwable failure) {
		try {
			String body = failure == null ? "OK"
					: "ERROR: " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
			Files.writeString(markerFile(out).toPath(), body, StandardCharsets.UTF_8);
		} catch (Throwable t) {
			LOGGER.error("[renderer] 写完成标记失败", t);
		}
	}

	private static RenderSettings buildSettings(File out) {
		int width = Integer.getInteger("opencraft.render.width", 1280);
		int height = Integer.getInteger("opencraft.render.height", 720);
		int fps = Integer.getInteger("opencraft.render.fps", 20);
		int bitRate = 8 << 20; // 8 Mbps
		return new RenderSettings(
				RenderSettings.RenderMethod.DEFAULT,
				RenderSettings.EncodingPreset.MP4_CUSTOM,
				width,
				height,
				fps,
				bitRate,
				out,
				false, // renderNameTags
				false, // includeAlphaChannel
				false, // stabilizeYaw
				false, // stabilizePitch
				false, // stabilizeRoll
				null,  // chromaKeyingColor
				360,   // sphericalFovX（非球面渲染不用）
				180,   // sphericalFovY
				false, // injectSphericalMetadata
				false, // depthMap
				false, // cameraPathExport
				RenderSettings.AntiAliasing.NONE,
				"",    // exportCommand：空 = ReplayMod 从 PATH 自动查找 ffmpeg
				RenderSettings.EncodingPreset.MP4_CUSTOM.getValue(),
				true   // highPerformance
		);
	}
}
