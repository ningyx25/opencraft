package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 自然世界任务「craft_wooden_pickaxe」：从空背包完成砍树并合成木镐。
 */
public class CraftWoodenPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_wooden_pickaxe";
	}

	@Override
	public String description() {
		return "从零收集木材并合成木镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你刚进入一个全新世界，身上什么都没有。"
				+ "请从零做一把木镐：用 player_find 搜索 minecraft:oak_log，"
				+ "选择距离最近且可达的坐标，不要把 ai_logo_block 当成原木。"
				+ "用 player_mine 收集原木；player_mine 是异步动作，在结果事件到达前"
				+ "不要执行 goto/teleport/其他动作。用 player_craft 依次合成木板、木棍、木镐。";
	}

	@Override
	public long timeoutMillis() {
		return 8 * 60_000L;
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:wooden_pickaxe") >= 1;
	}
}
