package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.plugins.ToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * system 提示词与动态上下文组装（参考 deepseek-harness 的 {@code system-prompt/} 包：有序小节拼装）。
 *
 * <p><b>KV 前缀缓存友好设计</b>：
 * <ul>
 *   <li><b>静态 System（{@link #staticSystem}）</b>：只含 {@code # Identity}、{@code # Capabilities}、
 *       {@code # Skills} 与 {@code # Message Protocol}。同预设同助手下跨轮恒定不变，满足
 *       "单条 system 且在开头"约束，使模型服务商的 KV 前缀缓存（Prompt Caching）能 100% 命中该前缀。</li>
 *   <li><b>动态 Game Context（{@link #formatGameContext}）</b>：包含玩家与助手的动态世界状态。
 *       移至会话消息中注入，不再污染静态 system，防止时间刻/坐标微变导致后续全部多轮历史消息缓存失效。</li>
 * </ul>
 *
 * <p><b>结构化约定</b>：整段用 Markdown，每个来源一个 {@code #} 大节、插件/状态片段用 {@code ##} 小节；
 * 动态数据段一律是 ```json 围栏的自描述 JSON。动态世界状态的<em>观察</em>（读世界/背包 → JSON +
 * 落 {@code logs/opencraft/*.json} 快照）在 {@link GameContext}（dsh {@code context/} 对应）。
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
	 * 组装静态 system 文本（# Identity → # Capabilities → # Skills → # Message Protocol）。
	 * <p>本方法为纯文本组装，不读世界状态，结果在同预设同助手（含技能/工具配置）下跨轮恒定不变，
	 * 是实现 KV 前缀缓存（Prompt Caching）高命中率的核心基础——<b>任何每轮变化的内容都不得加入
	 * 本方法输出</b>（历史教训：游戏上下文曾进 system，时间/坐标每轮微变导致全部多轮历史
	 * 每轮重新 prefill，45k 输入仅 4k 命中）。</p>
	 */
	public static String staticSystem(AiBlockConfig config, AgentDefinition agent) {
		StringBuilder sb = new StringBuilder();
		sb.append(persona(config, agent));
		if (agent != null) {
			String frags = agent.systemPromptFragments();
			if (frags != null && !frags.isBlank()) {
				sb.append("\n\n# Capabilities\n\n").append(frags);
			}
			// 内置技能(SKILL.md 文档):预设绑定(skills 列表) + 可用工具双重过滤后整节注入——
			// 教模型"某类任务怎么做"的结构化经验(如阶梯下沉挖法)
			String skills = com.swaydy.opencraft.agent.skills.SkillLibrary
					.promptsFragment(agent.skills(), agent.toolMap().keySet());
			if (!skills.isBlank()) {
				sb.append("\n\n").append(skills);
			}
		}
		// 消息协议（静态）:动态上下文移出 system 后,模型需要被告知怎么读消息流里的
		// 系统生成观测消息（[Event] 动作结果 / [Current State] 轻量状态快照）。
		sb.append("\n\n# Message Protocol\n\n")
				.append("Besides the player, the conversation may contain system-generated user messages you must understand: ")
				.append("`[Event]` messages report the outcome of your async actions (goto/mine/place/container_open) — ")
				.append("after one arrives, decide your next step from its result; ")
				.append("`[Current State]` messages carry a compact live snapshot (your position, what you hold, ")
				.append("the owner's position, time of day). The most recent observation always overrides older ones.");
		return sb.toString();
	}

	/**
	 * 组装动态游戏上下文片段（# Game Context：## Player State + ## Assistant State + 插件状态）。
	 * 任务首轮随提问消息注入消息流（见 {@code AgentRuntime.startLoop}）——不进 system。
	 */
	public static String formatGameContext(ServerPlayer player, AiAssistant assistant, AgentDefinition agent) {
		StringBuilder sb = new StringBuilder("# Game Context\n\n");
		sb.append(GameContext.playerState(player));
		ToolContext ctx = new ToolContext(player.level().getServer(), assistant, player,
				(ServerLevel) player.level());
		String assistantFrag = GameContext.assistantState(ctx);
		if (assistantFrag != null && !assistantFrag.isBlank()) {
			sb.append("\n\n").append(assistantFrag);
		}
		if (agent != null) {
			String ctxFrags = agent.gameContextFragments(ctx);
			if (ctxFrags != null && !ctxFrags.isBlank()) {
				sb.append("\n\n").append(ctxFrags);
			}
		}
		return sb.toString();
	}
}
