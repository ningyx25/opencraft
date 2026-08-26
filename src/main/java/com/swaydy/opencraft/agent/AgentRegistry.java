package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.plugins.presets.AssistantPlugin;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.agent.presets.ChatAgent;
import com.swaydy.opencraft.agent.presets.GeneralAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件 + Agent 预设的静态注册表（在 {@link OpenCraftMod#onInitialize()} 时初始化）。
 *
 * 插件是代码内注册的一等公民（第三方/脚本加载暂不支持）。Agent 预设供配置界面下拉选择，
 * 助手能力 = 其选中预设装配的插件之和。
 *
 * <p><b>与身体形态解耦</b>：预设只决定 LLM 行为（人设/工具/轮数）。助手本身一律是
 * 真正的 ServerPlayer bot（{@link com.swaydy.opencraft.assistant.player.AiAssistantPlayer}，
 * 像客户端一样进服），不因预设而改变形态。
 */
public final class AgentRegistry {
	private static final Map<String, AssistantPlugin> PLUGINS = new LinkedHashMap<>();
	private static final Map<String, AgentDefinition> AGENTS = new LinkedHashMap<>();
	private static boolean initialized = false;

	private AgentRegistry() {
	}

	/** 注册内置插件与 Agent 预设（幂等，只在首次调用时初始化）。 */
	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		registerPlugin(new com.swaydy.opencraft.plugins.presets.AssistantControlPlugin());
		// 玩家形态插件：假玩家（ServerPlayer 客户端形态）的真实玩家动作。
		// 注意：插件只是“能力单元”；身体形态（玩家 bot）不由预设决定——
		// 助手一律以真正的 ServerPlayer 身份进服，预设只装配 LLM 可调用的工具。
		registerPlugin(new com.swaydy.opencraft.plugins.presets.PlayerActionsPlugin());

		registerAgent(new ChatAgent().definition());
		registerAgent(new GeneralAgent().definition());
		// 一次性校验各预设绑定的技能名真实存在（防拼写错误导致技能静默不注入）
		validateBoundSkills();
	}

	/** 校验所有预设 skills 列表里的名字都在技能库中；未知的告警提示。 */
	private static void validateBoundSkills() {
		java.util.Set<String> known = new java.util.HashSet<>();
		for (com.swaydy.opencraft.agent.skills.Skill s
				: com.swaydy.opencraft.agent.skills.SkillLibrary.builtIns()) {
			known.add(s.name());
		}
		for (AgentDefinition def : AGENTS.values()) {
			for (String skill : def.skills()) {
				if (!known.contains(skill)) {
					OpenCraftMod.LOGGER.warn("[OpenCraft] Agent 预设 {} 绑定了未知技能 \"{}\""
							+ "（不在 skills/index.json 里）,该技能不会注入", def.id(), skill);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// 插件
	// ------------------------------------------------------------------

	public static void registerPlugin(AssistantPlugin plugin) {
		if (plugin == null || plugin.id() == null) {
			return;
		}
		if (PLUGINS.containsKey(plugin.id())) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 插件 {} 重复注册，已忽略", plugin.id());
			return;
		}
		PLUGINS.put(plugin.id(), plugin);
	}

	// ------------------------------------------------------------------
	// Agent 预设
	// ------------------------------------------------------------------

	public static void registerAgent(AgentDefinition agent) {
		if (agent == null || agent.id() == null) {
			return;
		}
		if (AGENTS.containsKey(agent.id())) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] Agent 预设 {} 重复注册，已忽略", agent.id());
			return;
		}
		AGENTS.put(agent.id(), agent);
	}

	public static AgentDefinition agent(String id) {
		return AGENTS.get(id);
	}

	/** 全部预设（有序，供配置界面下拉渲染）。 */
	public static List<AgentDefinition> agents() {
		return new ArrayList<>(AGENTS.values());
	}

	/**
	 * 从方块配置解析当前应使用的 Agent 预设：
	 * config.agent 为空或未知 → 回退到默认预设 {@value #DEFAULT_AGENT_ID} 并记日志。
	 */
	public static AgentDefinition resolveAgent(AiBlockConfig config) {
		String id = config == null ? null : config.agent;
		if (id != null && !id.isBlank()) {
			AgentDefinition def = AGENTS.get(id);
			if (def != null) {
				return def;
			}
			OpenCraftMod.LOGGER.warn("[OpenCraft] 配置指定了未知的 Agent 预设 \"{}\"，回退到默认",
					id);
		}
		AgentDefinition def = AGENTS.get(DEFAULT_AGENT_ID);
		return def != null ? def : new GeneralAgent().definition();
	}

	/** 默认 Agent 预设 id（无配置/未知时使用）。 */
	public static final String DEFAULT_AGENT_ID = "general_agent";
}