package com.swaydy.opencraft.plugins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 工具参数的小工具：安全地读取 x/y/z、字符串等，避免每个插件重复 try/catch。
 */
final class ToolArgs {
	private final JsonObject args;

	ToolArgs(JsonObject args) {
		this.args = args == null ? new JsonObject() : args;
	}

	/** 读取整数坐标分量；缺失/非法返回 fallback。 */
	int intOf(String key, int fallback) {
		JsonElement el = args.get(key);
		if (el == null || !el.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive p = el.getAsJsonPrimitive();
		try {
			if (p.isNumber()) {
				return p.getAsInt();
			}
			if (p.isString()) {
				return (int) Double.parseDouble(p.getAsString());
			}
		} catch (NumberFormatException ignored) {
		}
		return fallback;
	}

	/** 读取字符串参数；缺失/非法返回 fallback。 */
	String strOf(String key, String fallback) {
		JsonElement el = args.get(key);
		if (el == null || !el.isJsonPrimitive()) {
			return fallback;
		}
		String s = el.getAsString();
		return (s == null || s.isBlank()) ? fallback : s.trim();
	}

	/** 读取布尔参数；缺失/非法返回 fallback（字符串 "true"/"false" 也接受）。 */
	boolean boolOf(String key, boolean fallback) {
		JsonElement el = args.get(key);
		if (el == null || !el.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive p = el.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return p.getAsBoolean();
		}
		if (p.isString()) {
			return Boolean.parseBoolean(p.getAsString());
		}
		return fallback;
	}

	boolean has(String key) {
		return args.has(key);
	}
}
