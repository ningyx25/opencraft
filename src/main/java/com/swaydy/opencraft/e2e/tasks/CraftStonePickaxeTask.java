package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 自然世界任务「craft_stone_pickaxe」：完整早期工具链。
 *
 * <p>助手需自然找树→合成木镐→开采自然石头得到圆石→合成石镐。
 * 不预置平台、工作台或石头。</p>
 */
public class CraftStonePickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_stone_pickaxe";
	}

	@Override
	public String description() {
		return "从零完成早期工具链并合成石镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你刚进入一个全新世界，身上什么都没有。"
				+ "请从零做一把石镐：用 player_find 搜索 minecraft:oak_log，"
				+ "选择距离最近且可达的坐标并收集原木，合成木板、木棍、工作台和木镐。"
				+ "player_mine 是异步动作，在结果事件到达前不要执行 goto/teleport/其他动作。"
				+ "再用 player_find 搜索 minecraft:stone，用木镐真实开采圆石，最后合成石镐。";
	}

	@Override
	public long timeoutMillis() {
		return 10 * 60_000L;
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:stone_pickaxe") >= 1;
	}
}
