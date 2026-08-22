package com.swaydy.opencraft.agent;

/**
 * 工具结果文本裁剪器（参考 deepseek-harness 的 {@code compaction-tool-result-pruner} 插件）。
 *
 * <p>长工具结果（如 player_find 的目标清单）会撑大每轮请求的上下文；
 * 超过上限时保留头部 + 尾部、中间以省略标记替换——关键信息（开头摘要、结尾错误原因）
 * 不丢失，上下文增长有界。纯 Java、无 Minecraft 依赖，便于 JUnit 单测。
 */
public final class ToolResultPruner {
	/** 单条工具结果给模型看的字符硬上限。 */
	public static final int MAX_RESULT_CHARS = 1200;
	/** 超限时保留的头部字符数。 */
	public static final int HEAD_CHARS = 900;
	/** 超限时保留的尾部字符数。 */
	public static final int TAIL_CHARS = 200;

	private ToolResultPruner() {
	}

	/**
	 * 把工具执行结果整理成给模型看的最终文本：
	 * 统一以 {@code [工具名 成功/失败]} 标记开头（模型先读标记再读内容），
	 * 超长内容按 头/尾 保留裁剪。
	 *
	 * @param toolName 工具名（如 "player_find"）
	 * @param ok       执行是否成功
	 * @param message  工具返回的结果文本
	 */
	public static String toModelText(String toolName, boolean ok, String message) {
		String head = "[" + (toolName == null || toolName.isBlank() ? "?" : toolName)
				+ " " + (ok ? "success" : "failure") + "] ";
		String body = prune(message == null ? "" : message);
		return head + body;
	}

	/**
	 * 按默认预算裁剪文本：长度不超过 {@link #MAX_RESULT_CHARS} 时原样返回；
	 * 超过时保留 {@link #HEAD_CHARS} 头 + {@link #TAIL_CHARS} 尾，中间以省略标记替换。
	 */
	public static String prune(String text) {
		return prune(text, MAX_RESULT_CHARS, HEAD_CHARS, TAIL_CHARS);
	}

	/**
	 * 按指定预算裁剪文本。
	 *
	 * @param text      原文（null 视为空串）
	 * @param maxChars  总上限
	 * @param headChars 保留的头部字符数
	 * @param tailChars 保留的尾部字符数
	 */
	public static String prune(String text, int maxChars, int headChars, int tailChars) {
		if (text == null) {
			return "";
		}
		if (text.length() <= maxChars) {
			return text;
		}
		String marker = "\n…[… " + (text.length() - headChars - tailChars) + " chars omitted]…\n";
		return text.substring(0, headChars) + marker + text.substring(text.length() - tailChars);
	}
}
