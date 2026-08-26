package com.swaydy.opencraft.agent.presets;

import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.plugins.presets.AssistantPlugin;

import java.util.List;

/**
 * Agent 预设的基类（SPI）：预设 = 一组命名属性（id / 显示名 / 装配插件 / 人设 / 轮数上限 / 绑定技能），
 * 由 {@link #definition()} 组装成不可变的 {@link AgentDefinition} 供注册表/loop 消费。
 *
 * <p>与 {@link AssistantPlugin} 同思路：预设类（如 {@code ChatAgent} / {@code GeneralAgent}）继承本基类、
 * 以覆写访问器的方式声明自身属性，内置预设集中在 {@code agent/presets/} 子包，方便扩展新预设。
 *
 * <p>预设只决定助手的 LLM 行为（装配哪些插件、人设提示词、工具轮数上限、绑定技能），绝不决定身体形态
 * ——助手一律是玩家形态的假玩家 bot。
 */
public abstract class BaseAgent {
	/** 预设唯一 id，如 "chat_agent" / "general_agent"。 */
	public abstract String id();

	/** 配置界面显示名（翻译键）。 */
	public abstract String displayName();

	/** 该预设装配的插件（顺序即注册顺序，重名工具先注册者生效）。 */
	public abstract List<AssistantPlugin> plugins();

	/** 人设提示词（指导模型"怎么用工具、何时用工具"，位于 system 开头，自带 # 大节标题）。 */
	public abstract String personaPrompt();

	/** agentic loop 最大工具轮数。 */
	public abstract int maxToolRounds();

	/**
	 * 绑定的内置技能名列表（见 {@code agent/skills/SkillLibrary} 的 {@code skills/index.json}）；
	 * 默认不绑定任何技能。
	 */
	public List<String> skills() {
		return List.of();
	}

	/** 组装本预设的 {@link AgentDefinition}（供 {@link com.swaydy.opencraft.agent.AgentRegistry} 注册）。 */
	public final AgentDefinition definition() {
		return new AgentDefinition(id(), displayName(), plugins(), personaPrompt(), maxToolRounds(), skills());
	}
}