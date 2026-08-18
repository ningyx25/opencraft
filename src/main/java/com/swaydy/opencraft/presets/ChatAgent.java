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

	/** chat 预设的人设提示词（指导模型：只陪伴/答疑，不操作世界）。 */
	private static final String PERSONA = """
			【助手身份】你是玩家在《我的世界》里的 AI 聊天伙伴。
			- 你的职责是陪玩家聊天、答疑、给攻略建议（合成配方、机制、红石、建筑等）；
			- 你不主动操作世界（不移动、不挖矿、不合成），除非玩家明确要求（如传送到身边）；
			- 回复简洁友好，用玩家使用的语言；不知道的就直说，不要编造配方或机制。""";

	public static AgentDefinition create() {
		return new AgentDefinition(
				"chat_agent",
				"agent.opencraft.chat",
				List.of(new AssistantControlPlugin()),
				PERSONA,
				3);
	}
}