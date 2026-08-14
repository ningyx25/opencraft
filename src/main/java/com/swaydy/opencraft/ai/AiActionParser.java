package com.swaydy.opencraft.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 LLM 回复中的 [ACTION: ...] 标记，并把标记从正文中剥离。
 */
public final class AiActionParser {
	private static final Pattern ACTION_PATTERN =
			Pattern.compile("\\[ACTION:\\s*([a-zA-Z]+)(?:\\s+([^\\]]*))?\\]",
					Pattern.CASE_INSENSITIVE);

	private AiActionParser() {
	}

	/**
	 * 从文本中提取所有动作标记。
	 */
	public static List<AiAction> parse(String text) {
		List<AiAction> actions = new ArrayList<>();
		if (text == null) {
			return actions;
		}
		Matcher matcher = ACTION_PATTERN.matcher(text);
		while (matcher.find()) {
			String type = matcher.group(1).toLowerCase(Locale.ROOT);
			String args = matcher.group(2) == null ? "" : matcher.group(2).trim();
			AiAction action = buildAction(type, args);
			if (action != null) {
				actions.add(action);
			}
		}
		return actions;
	}

	/** 把动作标记从文本中移除（保留其余正文）。 */
	public static String stripActions(String text) {
		if (text == null) {
			return "";
		}
		String stripped = ACTION_PATTERN.matcher(text).replaceAll("").trim();
		// 清理残留的连续空行/多余空格
		return stripped.replaceAll("\\s+", " ").trim();
	}

	private static AiAction buildAction(String type, String args) {
		return switch (type) {
			case "give" -> {
				String[] parts = args.split("\\s+");
				if (parts.length >= 1 && !parts[0].isEmpty()) {
					int amount = 1;
					if (parts.length >= 2) {
						try {
							amount = Math.max(1, Math.min(640, Integer.parseInt(parts[1])));
						} catch (NumberFormatException ignored) {
							// 数量非法则给 1 个
						}
					}
					yield AiAction.give(parts[0], amount);
				}
				yield null;
			}
			case "time" -> {
				String mode = args.toLowerCase(Locale.ROOT);
				if (mode.equals("day") || mode.equals("night") || mode.equals("noon")
						|| mode.equals("sunset") || mode.equals("midnight")) {
					yield AiAction.time(mode);
				}
				yield null;
			}
			case "heal" -> AiAction.simple(AiAction.Type.HEAL);
			case "feed" -> AiAction.simple(AiAction.Type.FEED);
			case "xp" -> {
				try {
					yield AiAction.xp(Math.max(1, Math.min(1000, Integer.parseInt(args.trim()))));
				} catch (NumberFormatException e) {
					yield null;
				}
			}
			case "mode" -> {
				String mode = args.toLowerCase(Locale.ROOT);
				if (mode.equals("follow") || mode.equals("stay")) {
					yield AiAction.mode(mode);
				}
				yield null;
			}
			case "tp", "teleport", "come" -> AiAction.simple(AiAction.Type.TELEPORT);
			case "weather" -> {
				String weather = args.toLowerCase(Locale.ROOT);
				if (weather.equals("clear") || weather.equals("rain") || weather.equals("thunder")) {
					yield AiAction.weather(weather);
				}
				yield null;
			}
			default -> null;
		};
	}
}
