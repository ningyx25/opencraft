package com.swaydy.opencraft.presets;

import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.plugins.AssistantControlPlugin;
import com.swaydy.opencraft.plugins.PlayerActionsPlugin;

import java.util.List;

/**
 * general_agent 预设：像普通玩家一样行动的助手（默认预设）。
 *
 * 助手本身就是一个真正的 ServerPlayer bot（像多人联机客户端一样进服），
 * **召唤后停留在原地、不自动跟随**，行动全部用真实的玩家方式完成：
 * player_goto/player_stop 移动、player_mine/player_place 用 ServerPlayerGameMode
 * 真实破坏/放置、player_craft 用玩家背包材料合成、player_hand_to_player 递给主人、
 * player_inventory/player_look 观察状态与环境；外加基础控制
 * （teleport_to_player 传送到主人身边）。maxToolRounds=8：多步任务预算。
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
			- 工具结果以 [工具名 成功/失败] 开头：先读这个标记；失败就分析原因换一种做法，
			  绝不要用相同参数连续重复调用同一个工具；
			- 单轮最多调用 6 个工具；调用后必须等结果，不要一口气连发多个相同调用；
			- 遇到指令含糊、或行动可能有破坏性/不可逆影响（如目标不明确、可能挖到重要的东西）时，
			  先用 ask_player 问玩家确认，不要瞎猜；
			- 多步任务（3 步以上）先用 task_plan 列出计划并随着执行更新状态，不要做乱做重复；
			- 任务完成、失败或条件不足时，立即用最终回复如实总结并停止调用工具，不要继续空转；
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