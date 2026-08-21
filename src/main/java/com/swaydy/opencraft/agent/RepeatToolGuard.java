package com.swaydy.opencraft.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 重复工具调用守卫（参考 deepseek-harness 的 {@code repeat-tool-reminder} 插件）。
 *
 * <p>跟踪同一次任务循环里「连续、完全相同」的工具调用（工具名 + 参数 JSON 深度排序后一致），
 * 达到阈值时给模型注入提醒（先温和、后详细），打断模型"笨笨地重复同一动作"的死循环——
 * 例如反复用相同参数 player_goto 到同一个失败坐标、反复 player_mine 一个已挖掉的方块。
 *
 * <p>纯 Java、无 Minecraft 依赖，便于 JUnit 单测。每个任务（一次玩家提问）应新建一个实例
 * （或调用 {@link #reset()}），跨任务不累计。
 */
public final class RepeatToolGuard {
	/** 默认触发阈值：第 3 次温和提醒，第 5/8 次详细提醒（与 dsh-repeat-tool-reminder 默认一致）。 */
	private static final List<Integer> DEFAULT_THRESHOLDS = List.of(3, 5, 8);

	/** 第一次（温和）提醒：指出"在重复同一调用"，建议换做法。 */
	private static final String GENTLE_REMINDER =
			"[Reminder] You are repeatedly calling the same tool with exactly the same parameters. "
					+ "Carefully analyze the last result first: if the task is not done, try a different approach or different "
					+ "parameters instead of retrying identically; if you already have enough evidence, end the task with your final reply.";

	private final List<Integer> thresholds;
	private String lastKey = null;
	private int count = 0;

	public RepeatToolGuard() {
		this(DEFAULT_THRESHOLDS);
	}

	public RepeatToolGuard(List<Integer> thresholds) {
		if (thresholds == null || thresholds.isEmpty()) {
			throw new IllegalArgumentException("thresholds 不能为空");
		}
		this.thresholds = List.copyOf(new ArrayList<>(thresholds));
	}

	/**
	 * 记录一次工具调用；若本次调用撞上阈值，返回要给模型看的提醒文本，否则返回 null。
	 *
	 * @param toolName      工具名（如 "player_goto"）
	 * @param argumentsJson 模型填写的参数 JSON 原文（可为 null/空串）
	 */
	public String observe(String toolName, String argumentsJson) {
		String key = (toolName == null ? "" : toolName) + "\u0000" + canonicalize(argumentsJson);
		if (key.equals(lastKey)) {
			count++;
		} else {
			lastKey = key;
			count = 1;
		}
		if (thresholds.contains(count)) {
			if (count == thresholds.get(0)) {
				return GENTLE_REMINDER;
			}
			return detailedReminder(toolName, count, previewArguments(argumentsJson));
		}
		return null;
	}

	/** 新一轮任务（新的玩家提问）开始时重置计数。 */
	public void reset() {
		lastKey = null;
		count = 0;
	}

	/** 当前连续相同调用的次数（供日志/测试）。 */
	public int currentCount() {
		return count;
	}

	private static String detailedReminder(String toolName, int count, String argumentsPreview) {
		return "[Reminder] Repeated tool calls detected:\n"
				+ "- Tool: " + (toolName == null ? "?" : toolName) + "\n"
				+ "- Consecutive count: " + count + "\n"
				+ "- Arguments: " + argumentsPreview + "\n"
				+ "These repeated calls are making no progress. Stop calling this tool with these arguments: "
				+ "analyze the most recent result, choose a different action or different arguments, "
				+ "or if you have enough evidence, end the task immediately with your final reply.";
	}

	/**
	 * 参数 JSON 的规范化键：深度按键排序后序列化，使「键序不同但内容相同」的参数判为相同；
	 * 无法解析的原文按去掉首尾空白后的原始字符串兜底（与 dsh-repeat-tool-reminder 一致）。
	 */
	private static String canonicalize(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return "{}";
		}
		try {
			JsonElement el = JsonParser.parseString(argumentsJson);
			return sortValue(el).toString();
		} catch (Exception e) {
			return argumentsJson.trim();
		}
	}

	private static JsonElement sortValue(JsonElement el) {
		if (el == null || el.isJsonNull()) {
			return JsonParser.parseString("null");
		}
		if (el.isJsonArray()) {
			com.google.gson.JsonArray out = new com.google.gson.JsonArray();
			for (JsonElement item : el.getAsJsonArray()) {
				out.add(sortValue(item));
			}
			return out;
		}
		if (el.isJsonObject()) {
			com.google.gson.JsonObject obj = el.getAsJsonObject();
			com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			obj.keySet().stream().sorted()
					.forEach(k -> out.add(k, sortValue(obj.get(k))));
			return out;
		}
		return el;
	}

	/** 提醒里引用的参数预览：过长截断。 */
	private static String previewArguments(String argumentsJson) {
		String s = canonicalize(argumentsJson);
		return s.length() <= 200 ? s : s.substring(0, 200) + "…(+" + (s.length() - 200) + " chars)";
	}
}
