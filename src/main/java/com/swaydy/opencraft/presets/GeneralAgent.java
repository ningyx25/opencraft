package com.swaydy.opencraft.presets;

import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.plugins.AssistantControlPlugin;
import com.swaydy.opencraft.plugins.CombatPlugin;
import com.swaydy.opencraft.plugins.CraftingPlugin;
import com.swaydy.opencraft.plugins.InventoryPlugin;
import com.swaydy.opencraft.plugins.MiningPlugin;
import com.swaydy.opencraft.plugins.MovementPlugin;
import com.swaydy.opencraft.plugins.PerceptionPlugin;

import java.util.List;

/**
 * general_agent 预设：像普通玩家一样行动的助手。
 *
 * 装配全部 7 个插件——移动、感知、挖掘、物品、合成、战斗、基础控制——
 * 能自己在世界里走动、挖矿、合成物品、战斗，并把收获交给主人。
 * maxToolRounds=8：多步任务预算。
 */
public final class GeneralAgent {
	private GeneralAgent() {
	}

	/** general 预设的人设提示词（“观察→计划→行动→再观察”）。 */
	private static final String PERSONA = """
			【行动准则】你是一位能亲自动手的 AI 助手，像普通玩家一样在《我的世界》里活动。
			- 先观察（look_around）再行动：了解周围环境、自己背包、任务状态，再决定下一步；
			- 行动后要再观察（look_around）确认结果，不要假设工具一定成功——以工具返回的文本为准；
			- 一次只做一步：移动就 goto，挖掘就 mine，合成就 craft，物品交互就 hand_to_player；
			- 挖掘/移动/攻击是异步指令：下达后立即返回，助手会自己执行；随后用 look_around 确认是否完成；
			- 只为主人服务，绝不损害主人利益：不攻击玩家、不破坏主人的功能方块/建筑、不给别人东西；
			- 遇到无法完成的事（缺材料、路不通、打不过），诚实告诉主人并给建议，不要编造成功。""";

	public static AgentDefinition create() {
		return new AgentDefinition(
				"general_agent",
				"agent.opencraft.general",
				List.of(new AssistantControlPlugin(), new MovementPlugin(),
						new PerceptionPlugin(), new MiningPlugin(),
						new InventoryPlugin(), new CraftingPlugin(), new CombatPlugin()),
				PERSONA,
				8);
	}
}