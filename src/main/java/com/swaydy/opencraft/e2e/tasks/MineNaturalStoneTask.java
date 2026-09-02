package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 自然世界任务「mine_natural_stone」：给木镐，验证助手能自然寻找并开采真实石头。
 *
 * <p>不铺平台、不预放石头。助手需从地下挖掘路径开采自然 stone，并收集掉落的圆石。</p>
 */
public class MineNaturalStoneTask implements E2ETask {

	@Override
	public String id() {
		return "mine_natural_stone";
	}

	@Override
	public String description() {
		return "用木镐自然寻找并开采石头";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你有一把木镐，其他背包为空。"
				+ "请用 player_find 搜索 minecraft:stone。若石头在地下，向下挖掘接近它，"
				+ "然后用木镐开采自然石头并收集至少 3 个圆石。"
				+ "player_mine 是异步动作，在结果事件到达前不要执行 goto/teleport/其他动作。";
	}

	@Override
	public long timeoutMillis() {
		return 8 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		ctx.assistant().getInventory().add(new ItemStack(Items.WOODEN_PICKAXE, 1));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:cobblestone") >= 3;
	}
}
