package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 自然世界任务「place_workbench」：给工作台，验证真实放置与自然地表判断能力。
 *
 * <p>不预放放置点；助手需要在自然世界中找到合法空气格并放置工作台。</p>
 */
public class PlaceWorkbenchTask implements E2ETask {

	@Override
	public String id() {
		return "place_workbench";
	}

	@Override
	public String description() {
		return "在自然地面上放置工作台";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你背包里有一个工作台。"
				+ "请在出生点附近的自然地面上放置它：先用 player_find 找附近的 minecraft:grass_block "
				+ "作为地面参照，选择其上方为空气且可站的位置，再用 player_place 放置工作台。"
				+ "player_place 是异步动作，在结果事件到达前不要执行 goto/teleport/其他动作。";
	}

	@Override
	public long timeoutMillis() {
		return 5 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		ctx.assistant().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.hasBlockInRegion("minecraft:crafting_table", ctx.spawnPos(), 24);
	}
}
