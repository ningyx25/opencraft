package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 自然世界任务「craft_furnace」：早期资源加工链验收。
 *
 * <p>不埋矿石、不预放石头或熔炉；助手需在自然世界中开采 8 块石头并合成熔炉。</p>
 */
public class CraftFurnaceTask implements E2ETask {

	@Override
	public String id() {
		return "craft_furnace";
	}

	@Override
	public String description() {
		return "自然挖石并合成熔炉";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。"
				+ "你背包里有一个工作台和一把木镐。请先放置工作台，"
				+ "然后用 player_find 搜索 minecraft:stone；若石头在地下，向下挖掘接近它，"
				+ "用木镐挖到至少 8 个圆石，最后用工作台合成熔炉。"
				+ "player_mine/player_place 是异步动作，在对应结果事件到达前不要执行 goto/teleport/其他动作。";
	}

	@Override
	public long timeoutMillis() {
		return 12 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 与 taskPrompt 承诺一致：给助手一个工作台和一把木镐，任务只验收自然挖石 + 合成熔炉。
		ctx.assistant().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
		ctx.assistant().getInventory().add(new ItemStack(Items.WOODEN_PICKAXE, 1));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInAnyInventory("minecraft:furnace") >= 1;
	}
}
