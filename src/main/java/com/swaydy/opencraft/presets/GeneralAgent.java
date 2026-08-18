package com.swaydy.opencraft.presets;

import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.plugins.AssistantControlPlugin;
import com.swaydy.opencraft.plugins.PlayerActionsPlugin;

import java.util.List;

/**
 * general_agent 预设：像普通玩家一样行动的助手（默认预设）。
 *
 * 助手本身就是一个真正的 ServerPlayer bot（像多人联机客户端一样进服），
 * 因此这里的“行动”全部用真实的玩家方式完成：player_goto/player_stop 移动、
 * player_mine/player_place 用 ServerPlayerGameMode 真实破坏/放置、
 * player_craft 用玩家背包材料合成、player_hand_to_player 递给主人、
 * player_inventory/player_look 观察状态与环境；外加基础控制
 * （set_mode 跟随/待命、teleport_to_player 传送到主人身边）。
 * maxToolRounds=8：多步任务预算。
 */
public final class GeneralAgent {
	private GeneralAgent() {
	}

	/** general 预设的人设提示词（“观察→计划→行动→再观察”，以玩家身份行动）。 */
	private static final String PERSONA = """
			【行动准则】你是一位能以真正玩家身份亲自动手的 AI 助手，在《我的世界》里行动。
			- 先观察（player_look）再行动：了解周围环境、自己的玩家背包、是否在移动，再决定下一步；
			- 行动后要再观察（player_look）确认结果，不要假设工具一定成功——以工具返回的文本为准；
			- 一次只做一步：移动就 player_goto，挖掘就 player_mine，放置就 player_place，
			  合成就 player_craft，物品交互就 player_hand_to_player；
			- 移动/挖掘/放置是异步指令：下达后立即返回，助手会自己走过去执行；随后用 player_look 确认；
			- 只为主人服务，绝不损害主人利益：不攻击玩家、不破坏主人的功能方块/建筑、不给别人东西；
			- 遇到无法完成的事（缺材料、路不通、打不过），诚实告诉主人并给建议，不要编造成功。""";

	public static AgentDefinition create() {
		return new AgentDefinition(
				"general_agent",
				"agent.opencraft.general",
				List.of(new AssistantControlPlugin(), new PlayerActionsPlugin()),
				PERSONA,
				8);
	}
}