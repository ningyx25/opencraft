package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.plugins.AssistantPlugin;
import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolDefinition;

import com.swaydy.opencraft.OpenCraftMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 预设：插件的命名组合 + 人设提示词 + agentic loop 参数。
 *
 * 助手的能力 = 其选中的预设装配的插件之和（tools + system/skill 提示词 + 游戏上下文 + 实体 Goal）。
 *
 * @param id           预设唯一 id，如 "chat_agent" / "general_agent"
 * @param displayName  配置界面显示名（翻译键）
 * @param plugins      该预设装配的插件（顺序即注册顺序，重名工具先注册者生效并告警）
 * @param personaPrompt 人设提示词（指导模型“怎么用工具、何时用工具”，位于 system 开头）
 * @param maxToolRounds agentic loop 最大工具轮数
 */
public record AgentDefinition(String id, String displayName, List<AssistantPlugin> plugins,
                              String personaPrompt, int maxToolRounds) {

	/** 汇总全部工具：按插件顺序，重名时先注册者生效并记 WARN 日志。 */
	public Map<String, ToolDefinition> toolMap() {
		Map<String, ToolDefinition> map = new LinkedHashMap<>();
		if (plugins == null) {
			return map;
		}
		for (AssistantPlugin plugin : plugins) {
			for (ToolDefinition tool : plugin.tools()) {
				String name = tool.name();
				if (name == null || name.isBlank()) {
					OpenCraftMod.LOGGER.warn("[OpenCraft] 插件 {} 注册了无名字的工具，已忽略",
							plugin.id());
					continue;
				}
				if (map.containsKey(name)) {
					OpenCraftMod.LOGGER.warn("[OpenCraft] 工具 {} 在预设 {} 中重复（来自插件 {}），"
							+ "使用先注册的实现", name, id, plugin.id());
					continue;
				}
				map.put(name, tool);
			}
		}
		return map;
	}

	/** 汇总各插件的 system 提示词片段（非空片段按插件顺序用换行拼成一段）。 */
	public String systemPromptFragments() {
		StringBuilder sb = new StringBuilder();
		if (plugins != null) {
			for (AssistantPlugin plugin : plugins) {
				String frag = plugin.systemPromptFragment();
				if (frag != null && !frag.isBlank()) {
					if (sb.length() > 0) {
						sb.append('\n');
					}
					sb.append(frag);
				}
			}
		}
		return sb.toString();
	}

	/** 汇总各插件的游戏上下文片段（按插件顺序，非空片段拼成一段）。 */
	public String gameContextFragments(ToolContext ctx) {
		StringBuilder sb = new StringBuilder();
		if (plugins != null) {
			for (AssistantPlugin plugin : plugins) {
				String frag = plugin.gameContextFragment(ctx);
				if (frag != null && !frag.isBlank()) {
					if (sb.length() > 0) {
						sb.append('\n');
					}
					sb.append(frag);
				}
			}
		}
		return sb.toString();
	}

	/** 该预设暴露给模型的工具列表（OpenAI tools schema，每个都是 {"type":"function",...}）。 */
	public List<com.google.gson.JsonObject> toolsJson() {
		List<com.google.gson.JsonObject> result = new ArrayList<>();
		for (ToolDefinition tool : toolMap().values()) {
			com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
			fn.addProperty("name", tool.name());
			fn.addProperty("description", tool.description());
			fn.add("parameters", tool.parameters());
			com.google.gson.JsonObject schema = new com.google.gson.JsonObject();
			schema.addProperty("type", "function");
			schema.add("function", fn);
			result.add(schema);
		}
		return result;
	}
}