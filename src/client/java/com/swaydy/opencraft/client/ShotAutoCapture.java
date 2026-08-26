package com.swaydy.opencraft.client;

import com.swaydy.opencraft.OpenCraftMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

/**
 * 客户端侧"评测截图自动抓取"：配合无头 e2e（Xvfb + 软渲染真客户端）使用。
 *
 * <p>用法：客户端以 {@code --quickPlayMultiplayer 127.0.0.1:25565} 自动进服后，
 * 每 N 秒调一次 {@link Screenshot#grab} 存一张原版画质截图（画面 = 服务器每 tick
 * 把本客户端玩家粘到 AI 助手眼睛位置/朝向，所以看到的就是助手第一人称视角）。
 * 截图落在客户端游戏目录 {@code run/screenshots/}。</p>
 *
 * <p>触发：环境变量 {@code OPEN_CRAFT_SHOT_AUTOCAPTURE=<秒数>}（或系统属性
 * {@code -Dopencraft.shot.autocapture=<秒数>}）。由 {@link OpenCraftModClient}
 * 在初始化时调用 {@link #init()}。</p>
 */
public final class ShotAutoCapture {
	/** 上次截图时间（毫秒）。 */
	private static long lastShotMs;

	private ShotAutoCapture() {
	}

	/** 注册截图定时器（未启用时 no-op）。 */
	public static void init() {
		long intervalMs = intervalMs();
		if (intervalMs <= 0) {
			return;
		}
		OpenCraftMod.LOGGER.info("[OpenCraft] 评测截图自动抓取已启用：每 {}s 一张", intervalMs / 1000);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) {
				return; // 还没进服
			}
			long now = System.currentTimeMillis();
			if (lastShotMs == 0) {
				lastShotMs = now;
			}
			if (now - lastShotMs < intervalMs) {
				return;
			}
			lastShotMs = now;
			capture(client);
		});
	}

	/** 截图间隔（毫秒）；未配置或非法时返回 0（禁用）。 */
	private static long intervalMs() {
		String sec = System.getenv("OPEN_CRAFT_SHOT_AUTOCAPTURE");
		if (sec == null || sec.isBlank()) {
			sec = System.getProperty("opencraft.shot.autocapture");
		}
		if (sec == null || sec.isBlank()) {
			return 0;
		}
		try {
			long s = Long.parseLong(sec.trim());
			return s > 0 ? s * 1000L : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** 抓一张当前帧到 run/screenshots/。 */
	private static void capture(Minecraft client) {
		try {
			Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), (Component c) -> {
				// 截图完成的反馈消息（控制台可见即可）
				OpenCraftMod.LOGGER.info("[OpenCraft] 评测截图已保存: {}", c.getString());
			});
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 截图失败: {}", e.toString());
		}
	}
}
