package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.plugins.ToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * system 提示词组装（参考 deepseek-harness 的 {@code system-prompt/} 包：有序小节拼装）。
 *
 * <p>本类只负责<b>整段 system 的小节组装</b>，不读世界：
 * <pre>
 *   # Identity（基础人设 + 名字 + 预设 personaPrompt）
 *   # Capabilities（各插件的 systemPromptFragment）
 *   # Skills（内置技能 SKILL.md，按绑定 + 可用工具过滤）
 *   # Game Context
 *     ## Player State（{@link GameContext#playerState}）
 *     ## Assistant State（{@link GameContext#assistantState}）
 *     ## 插件自有 gameContextFragment
 *   # Current Task Plan（task_plan 数据，systemWithPlan 追加）
 * </pre>
 *
 * <p><b>结构化约定</b>：整段用 Markdown，每个来源一个 {@code #} 大节、插件/状态片段用 {@code ##} 小节；
 * 动态数据段一律是 ```json 围栏的自描述 JSON。动态世界状态的<em>观察</em>（读世界/背包 → JSON +
 * 落 {@code logs/opencraft/*.json} 快照）在 {@link GameContext}（dsh {@code context/} 对应），
 * 各插件片段在各插件内、预设 persona 在 {@code agent/presets} 各预设类内、守卫文案在各守卫/执行器内。
 */
public final class Prompts {
	private Prompts() {
	}

	/**
	 * 所有预设共享的基础人设（非配置,随代码内置）:简短友好 + 用玩家语言。
	 * 具体"怎么做事/何时用工具"由各预设的 personaPrompt 与插件提示词决定。
	 */
	private static final String BASE_PERSONA = """
			You are an AI game assistant living in Minecraft, accompanying the player through adventures, building, and survival —
			a reliable and slightly humorous friend. Keep replies short (usually no more than 3~4 sentences) and answer in the language the player uses.""";

	/** 历史压缩的指令（拼在旧消息区段之后,要求模型只输出摘要正文——不能要求 markdown,否则改变摘要格式）。 */
	static final String COMPACT_INSTRUCTION = """
			Please compress the chat history between you and the player above into a short memory summary (within 150 words).
			Keep: important information about the player (name, needs, agreements, progress, todos), things you promised, unfinished tasks, key coordinates/items.
			No small talk, no line-by-line retelling — only distilled key points. Output only the summary text, with no prefix or formatting.""";

	/**
	 * 人设与名字段（{@code # Identity}）:基础人设 + {@code ## Name} 名字指令 + 预设各自的准则。
	 */
	public static String persona(AiBlockConfig config, AgentDefinition agent) {
		StringBuilder sb = new StringBuilder("# Identity\n\n");
		sb.append(BASE_PERSONA);
		sb.append("\n\n## Name\n\nYour name is ").append(config.effectiveName())
				.append(". Always refer to yourself by this name and use no other.");
		if (agent != null && agent.personaPrompt() != null && !agent.personaPrompt().isBlank()) {
			sb.append("\n\n").append(agent.personaPrompt());
		}
		return sb.toString();
	}

	/**
	 * 组装完整 system 文本（Markdown 结构）:
	 * 人设（# Identity）→ 插件能力（# Capabilities）→ 内置技能（# Skills）→
	 * 游戏上下文（# Game Context：## Player State + ## Assistant State + 插件状态）。
	 * 动态状态段由 {@link GameContext} 观察世界产出。
	 */
	public static String system(AiBlockConfig config, AgentDefinition agent,
	                            ServerPlayer player, AiAssistant assistant) {
		StringBuilder sb = new StringBuilder();
		sb.append(persona(config, agent));
		String frags = agent.systemPromptFragments();
		if (!frags.isBlank()) {
			sb.append("\n\n# Capabilities\n\n").append(frags);
		}
		// 内置技能(SKILL.md 文档):预设绑定(skills 列表) + 可用工具双重过滤后整节注入——
		// 教模型"某类任务怎么做"的结构化经验(如阶梯下沉挖法)
		String skills = com.swaydy.opencraft.agent.skills.SkillLibrary
				.promptsFragment(agent.skills(), agent.toolMap().keySet());
		if (!skills.isBlank()) {
			sb.append("\n\n").append(skills);
		}
		sb.append("\n\n# Game Context\n\n").append(GameContext.playerState(player));
		ToolContext ctx = new ToolContext(player.level().getServer(), assistant, player,
				(ServerLevel) player.level());
		String assistantFrag = GameContext.assistantState(ctx);
		if (assistantFrag != null && !assistantFrag.isBlank()) {
			sb.append("\n\n").append(assistantFrag);
		}
		String ctxFrags = agent.gameContextFragments(ctx);
		if (!ctxFrags.isBlank()) {
			sb.append("\n\n").append(ctxFrags);
		}
		return sb.toString();
	}

	/** 在基础 system 上追加当前任务计划（# Current Task Plan + ```json;planText 本身是 JSON）。 */
	public static String systemWithPlan(AiBlockConfig config, AgentDefinition agent,
	                                    ServerPlayer player, AiAssistant assistant, String planText) {
		String base = system(config, agent, player, assistant);
		if (planText == null || planText.isBlank()) {
			return base;
		}
		return base + "\n\n# Current Task Plan\n\n```json\n" + planText + "\n```";
	}
}
