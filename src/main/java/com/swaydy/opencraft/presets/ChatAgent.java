package com.swaydy.opencraft.presets;

import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.plugins.AssistantControlPlugin;

import java.util.List;

/**
 * chat_agent 预设：纯聊天助手。
 *
 * 只装配基础控制插件（teleport_to_player），没有移动/挖掘/合成等
 * 世界操作工具——适合只想安心聊天、不希望助手乱动世界的玩家。
 * maxToolRounds=3（容纳偶尔的 ask_player 澄清提问 + 恢复后的收尾）。
 */
public final class ChatAgent {
	private ChatAgent() {
	}

	/** chat 预设的人设提示词（指导模型：只陪伴/答疑，不操作世界;自带 # 大节）。 */
	private static final String PERSONA = """
			# Chat Guidelines

			You are the player's AI chat companion in Minecraft.

			- Your job is to chat with the player, answer questions, and give tips (crafting recipes, mechanics, redstone, building, etc.)
			- You do not act on the world on your own (no moving, mining, or crafting) unless the player explicitly asks (e.g. teleporting to them)
			- Reply concisely and friendly, using the language the player speaks; if you don't know something, say so directly — never invent recipes or mechanics.""";

	public static AgentDefinition create() {
		return new AgentDefinition(
				"chat_agent",
				"agent.opencraft.chat",
				List.of(new AssistantControlPlugin()),
				PERSONA,
				3);
	}
}