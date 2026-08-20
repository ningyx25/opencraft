package com.swaydy.opencraft.client.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界内的 AI 助手流式回复浮层（HUD）。
 *
 * 服务端把所有入口（/opencraft ask、配置页聊天窗口、右键互动界面、纯聊天 Agent）
 * 的流式回复都以 {@code AssistantStreamPayload}（sessionId + 文本快照 + done）
 * 推到这里：玩家在世界里就能看到回复逐字出现——多行自动换行、完整内容可见、
 * 带 ▍ 光标；回复完成后浮层短暂停留并淡出；最终完整文本仍照常广播进世界聊天。
 *
 * <p><b>sessionId 路由</b>：客户端只保留见过的最大会话号，旧会话（被新提问、
 * 或另一个并发助手）的迟到包一律忽略——避免多条并发流在这个浮层上互相覆盖串扰。
 */
public final class AssistantStreamOverlay {
	private AssistantStreamOverlay() {
	}

	private static int sessionId = -1;
	private static String name = "";
	private static String text = "";
	private static boolean done;
	private static long doneAtMs;
	/** 完成后浮层停留多久再淡出（毫秒）。 */
	private static final long KEEP_MS = 5000L;
	/** 淡出时长（毫秒）。 */
	private static final long FADE_MS = 1000L;
	/** 最多显示的文本行数（超出折叠，末尾加省略标记）。 */
	private static final int MAX_LINES = 6;

	/** 收到一条流式包（服务端线程之外调用：客户端执行队列内回调）。 */
	public static void update(int newSession, String newName, String snapshot, boolean isDone) {
		if (newSession < sessionId) {
			return; // 旧会话迟到包：忽略
		}
		if (newSession > sessionId) {
			sessionId = newSession;
			name = newName == null ? "" : newName;
			text = "";
			done = false;
			doneAtMs = 0L;
		}
		text = snapshot == null ? "" : snapshot;
		if (isDone) {
			done = true;
			doneAtMs = System.currentTimeMillis();
		} else {
			done = false;
		}
	}

	public static void clear() {
		sessionId = -1;
		name = "";
		text = "";
		done = false;
		doneAtMs = 0L;
	}

	/** HUD 回调：渲染世界内浮层（快捷栏上方，居中，多行换行）。 */
	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		if (sessionId < 0 || text.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// 离开世界/服务器或打开界面时清理或隐藏：防止旧会话文本跨世界残留
		if (mc.level == null || mc.player == null) {
			clear();
			return;
		}
		// 有界面打开时不渲染（界面自带对话区；浮层主要服务于世界内观看）
		if (mc.screen != null) {
			return;
		}
		long age = System.currentTimeMillis() - doneAtMs;
		if (done && age > KEEP_MS) {
			clear();
			return;
		}
		float alpha = 1f;
		if (done && age > KEEP_MS - FADE_MS) {
			alpha = Math.max(0f, (KEEP_MS - age) / (float) FADE_MS);
			if (alpha <= 0f) {
				clear();
				return;
			}
		}
		Font font = mc.font;
		// 带助手名前缀（如 [小智] 回复…），多助手时一眼可辨是谁在说话
		String prefix = name == null || name.isBlank() ? "" : "[" + name + "] ";
		String display = prefix + (done ? text : text + "▍");
		int maxWidth = Math.max(80, Math.min(360, mc.getWindow().getGuiScaledWidth() - 40));
		List<FormattedCharSequence> lines = new ArrayList<>(
				font.split(Component.literal(display), maxWidth));
		boolean truncated = false;
		if (lines.size() > MAX_LINES) {
			truncated = true;
			lines = new ArrayList<>(lines.subList(0, MAX_LINES));
		}
		int lineHeight = font.lineHeight;
		int pad = 5;
		int linePad = 3;
		int totalH = lines.size() * lineHeight + linePad * 2;
		int maxLineW = 0;
		for (FormattedCharSequence line : lines) {
			maxLineW = Math.max(maxLineW, font.width(line));
		}
		if (truncated) {
			maxLineW = Math.max(maxLineW, font.width("…"));
		}
		int boxW = maxLineW + pad * 2;
		int bottomY = mc.getWindow().getGuiScaledHeight() - 58; // 快捷栏上方
		int x0 = (mc.getWindow().getGuiScaledWidth() - boxW) / 2;
		int y0 = bottomY - totalH;

		int bgAlpha = (int) (0xAA * alpha) << 24;
		graphics.fill(x0, y0, x0 + boxW, y0 + totalH, bgAlpha);
		int textColor = 0xFFFFFF | ((int) (255 * alpha) << 24);
		int y = y0 + linePad;
		for (FormattedCharSequence line : lines) {
			graphics.drawString(font, line, x0 + pad, y, textColor);
			y += lineHeight;
		}
		if (truncated) {
			graphics.drawString(font, "…", x0 + pad, y - lineHeight, textColor);
		}
	}
}
