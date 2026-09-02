package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 自然世界任务「craft_workbench」：从空背包砍树并合成工作台。
 *
 * <p>不预给原木、不预放工作台；助手必须自己寻找自然树木并完成原木→木板→工作台。</p>
 */
public class CraftWorkbenchTask implements E2ETask {

	@Override
	public String id() {
		return "craft_workbench";
	}

	@Override
	public String description() {
		return "从零收集木材并合成工作台";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你刚进入一个全新世界，身上什么都没有。"
				+ "请从零开始合成一个工作台：先用 player_find 找自然生成的原木，"
				+ "选择列表中距离最近且可达的原木，用 player_mine 收集原木。"
				+ "挖掘是异步动作，在结果事件到达前不要执行 goto/teleport/其他动作。"
				+ "拿到原木后，用 player_craft 依次合成木板和工作台。不要猜坐标，不要要求我给材料。";
	}

	@Override
	public long timeoutMillis() {
		return 6 * 60_000L;
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:crafting_table") >= 1;
	}
}
