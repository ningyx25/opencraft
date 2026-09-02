package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 自然世界任务「chop_tree」：寻找出生点附近的自然橡树并收集原木。
 *
 * <p>助手从真实新玩家状态开始：空背包、无工具。验证只看真实背包结果，
 * 不提示树的位置，也不预置任何方块。</p>
 */
public class ChopTreeTask implements E2ETask {

	@Override
	public String id() {
		return "chop_tree";
	}

	@Override
	public String description() {
		return "在自然出生点附近砍树并收集原木";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你刚进入一个全新世界，身上什么都没有。"
				+ "附近自然生成的资源足够完成任务。请用 player_find 搜索 minecraft:oak_log，"
				+ "选择距离最近且可达的坐标，不要把 ai_logo_block 当成原木。"
				+ "用 player_mine 真实挖掘；player_mine 是异步动作，在结果事件到达前"
				+ "不要执行 goto/teleport/其他动作。收集至少 3 个 oak_log。";
	}

	@Override
	public long timeoutMillis() {
		return 6 * 60_000L;
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:oak_log") >= 3;
	}
}
