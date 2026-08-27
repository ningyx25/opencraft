package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 内置 e2e 任务「craft_diamond_pickaxe」：挖钻石矿，合成钻石镐（钻石镐任务线最终节点）。
 *
 * <p>场景：给铁镐 + 2 根木棍 + 平台放好工作台 + 平台埋钻石矿石×3。助手用铁镐挖钻石矿石→
 * 钻石，再用钻石+木棍在工做台合成钻石镐。验证：背包/主人有 {@code diamond_pickaxe}。</p>
 */
public class CraftDiamondPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_diamond_pickaxe";
	}

	@Override
	public String description() {
		return "挖钻石矿，合成钻石镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有工作台，平台上有钻石矿石。"
				+ "你背包里有铁镐和 2 根木棍。请用铁镐挖钻石矿石得到钻石，"
				+ "再用钻石和木棍在工作台合成一把钻石镐。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.placeWorkbench(ctx);
		// 平台埋钻石矿石 ×3
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.DIAMOND_ORE, i);
		}
		ctx.assistant().getInventory().add(new ItemStack(Items.IRON_PICKAXE, 1));
		ctx.assistant().getInventory().add(new ItemStack(Items.STICK, 2));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInInventory("minecraft:diamond_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:diamond_pickaxe") >= 1;
	}
}